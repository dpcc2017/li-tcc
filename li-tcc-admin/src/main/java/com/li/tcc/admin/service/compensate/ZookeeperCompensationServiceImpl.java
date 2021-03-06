package com.li.tcc.admin.service.compensate;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.li.tcc.common.bean.adapter.CoordinatorRepositoryAdapter;
import com.li.tcc.common.exception.TccException;
import com.li.tcc.common.serializer.ObjectSerializer;
import com.li.tcc.common.utils.DateUtils;
import com.li.tcc.common.utils.RepositoryPathUtils;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import com.google.common.collect.Lists;
import com.li.tcc.admin.helper.ConvertHelper;
import com.li.tcc.admin.helper.PageHelper;
import com.li.tcc.admin.page.CommonPager;
import com.li.tcc.admin.query.CompensationQuery;
import com.li.tcc.admin.service.CompensationService;
import com.li.tcc.admin.vo.TccCompensationVO;

/**
 * zookeeper impl
 * 
 * @author yuan.li
 */
public class ZookeeperCompensationServiceImpl implements CompensationService {

	private final ZooKeeper zooKeeper;

	private final ObjectSerializer objectSerializer;

	public ZookeeperCompensationServiceImpl(ZooKeeper zooKeeper, ObjectSerializer objectSerializer) {
		this.zooKeeper = zooKeeper;
		this.objectSerializer = objectSerializer;
	}

	@Override
	public CommonPager<TccCompensationVO> listByPage(final CompensationQuery query) {
		CommonPager<TccCompensationVO> voCommonPager = new CommonPager<>();
		final int currentPage = query.getPageParameter().getCurrentPage();
		final int pageSize = query.getPageParameter().getPageSize();
		int start = (currentPage - 1) * pageSize;
		final String rootPath = RepositoryPathUtils.buildZookeeperPathPrefix(query.getApplicationName());
		List<String> zNodePaths;
		List<TccCompensationVO> voList;
		int totalCount;
		try {
			// 如果只查 重试条件的
			if (StringUtils.isBlank(query.getTransId()) && Objects.nonNull(query.getRetry())) {
				zNodePaths = zooKeeper.getChildren(rootPath, false);
				final List<TccCompensationVO> all = findAll(zNodePaths, rootPath);
				final List<TccCompensationVO> collect = all.stream()
						.filter(vo -> vo.getRetriedCount() < query.getRetry()).collect(Collectors.toList());
				totalCount = collect.size();
				voList = collect.stream().skip(start).limit(pageSize).collect(Collectors.toList());
			} else if (StringUtils.isNoneBlank(query.getTransId()) && Objects.isNull(query.getRetry())) {
				zNodePaths = Lists.newArrayList(query.getTransId());
				totalCount = zNodePaths.size();
				voList = findAll(zNodePaths, rootPath);
			} else if (StringUtils.isNoneBlank(query.getTransId()) && Objects.nonNull(query.getRetry())) {
				zNodePaths = Lists.newArrayList(query.getTransId());
				totalCount = zNodePaths.size();
				voList = findAll(zNodePaths, rootPath).stream().filter(vo -> vo.getRetriedCount() < query.getRetry())
						.collect(Collectors.toList());
			} else {
				zNodePaths = zooKeeper.getChildren(rootPath, false);
				totalCount = zNodePaths.size();
				voList = findByPage(zNodePaths, rootPath, start, pageSize);
			}
			voCommonPager.setPage(PageHelper.buildPage(query.getPageParameter(), totalCount));
			voCommonPager.setDataList(voList);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return voCommonPager;
	}

	@Override
	public Boolean batchRemove(final List<String> ids, final String appName) {
		if (CollectionUtils.isEmpty(ids) || StringUtils.isBlank(appName)) {
			return Boolean.FALSE;
		}
		final String rootPathPrefix = RepositoryPathUtils.buildZookeeperPathPrefix(appName);
		ids.stream()
				.map(id -> {
					try {
						final String path = RepositoryPathUtils.buildZookeeperRootPath(rootPathPrefix, id);
						byte[] content = zooKeeper.getData(path, false, new Stat());
						final CoordinatorRepositoryAdapter adapter = objectSerializer.deSerialize(content,
								CoordinatorRepositoryAdapter.class);
						zooKeeper.delete(path, adapter.getVersion());
						return 1;
					} catch (Exception e) {
						e.printStackTrace();
						return -1;
					}
				}).count();
		return Boolean.TRUE;
	}

	@Override
	public Boolean updateRetry(final String id, final Integer retry, final String appName) {
		if (StringUtils.isBlank(id) || StringUtils.isBlank(appName) || Objects.isNull(retry)) {
			return Boolean.FALSE;
		}
		final String rootPathPrefix = RepositoryPathUtils.buildZookeeperPathPrefix(appName);
		final String path = RepositoryPathUtils.buildZookeeperRootPath(rootPathPrefix, id);
		try {
			byte[] content = zooKeeper.getData(path, false, new Stat());
			final CoordinatorRepositoryAdapter adapter = objectSerializer.deSerialize(content,
					CoordinatorRepositoryAdapter.class);
			adapter.setLastTime(DateUtils.getDateYYYY());
			adapter.setRetriedCount(retry);
			zooKeeper.create(path, objectSerializer.serialize(adapter), ZooDefs.Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT);
			return Boolean.TRUE;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return Boolean.FALSE;
	}

	private List<TccCompensationVO> findAll(final List<String> zNodePaths, final String rootPath) {
		return zNodePaths.stream().filter(StringUtils::isNoneBlank)
				.map(zNodePath -> buildByNodePath(rootPath, zNodePath)).collect(Collectors.toList());
	}

	private List<TccCompensationVO> findByPage(final List<String> zNodePaths, final String rootPath, final int start,
			final int pageSize) {
		return zNodePaths.stream().skip(start).limit(pageSize).filter(StringUtils::isNoneBlank)
				.map(zNodePath -> buildByNodePath(rootPath, zNodePath)).collect(Collectors.toList());
	}

	private TccCompensationVO buildByNodePath(final String rootPath, final String zNodePath) {
		try {
			byte[] content = zooKeeper.getData(RepositoryPathUtils.buildZookeeperRootPath(rootPath, zNodePath), false,
					new Stat());
			final CoordinatorRepositoryAdapter adapter = objectSerializer.deSerialize(content,
					CoordinatorRepositoryAdapter.class);
			return ConvertHelper.buildVO(adapter);
		} catch (KeeperException | InterruptedException | TccException e) {
			e.printStackTrace();
		}
		return null;
	}

}
