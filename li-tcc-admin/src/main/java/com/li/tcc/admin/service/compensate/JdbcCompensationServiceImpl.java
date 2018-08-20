package com.li.tcc.admin.service.compensate;

import com.li.tcc.common.constant.CommonConstant;
import com.li.tcc.common.utils.DateUtils;
import com.li.tcc.common.utils.DbTypeUtils;
import com.li.tcc.common.utils.RepositoryPathUtils;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.li.tcc.admin.helper.PageHelper;
import com.li.tcc.admin.page.CommonPager;
import com.li.tcc.admin.page.PageParameter;
import com.li.tcc.admin.query.CompensationQuery;
import com.li.tcc.admin.service.CompensationService;
import com.li.tcc.admin.vo.TccCompensationVO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * jdbc impl
 * 
 * @author yuan.li
 */
public class JdbcCompensationServiceImpl implements CompensationService {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String dbType;

	@Override
	public CommonPager<TccCompensationVO> listByPage(final CompensationQuery query) {
		final String tableName = RepositoryPathUtils.buildDbTableName(query.getApplicationName());
		final PageParameter pageParameter = query.getPageParameter();
		StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder
				.append("select trans_id,target_class,target_method,confirm_method,cancel_method,"
						+ " retried_count,create_time,last_time,version from ").append(tableName)
				.append(" where 1= 1 ");
		if (StringUtils.isNoneBlank(query.getTransId())) {
			sqlBuilder.append(" and trans_id = ").append(query.getTransId());
		}
		if (Objects.nonNull(query.getRetry())) {
			sqlBuilder.append(" and retried_count < ").append(query.getRetry());
		}
		final String sql = buildPageSql(sqlBuilder.toString(), pageParameter);
		CommonPager<TccCompensationVO> pager = new CommonPager<>();
		final List<Map<String, Object>> mapList = jdbcTemplate.queryForList(sql);
		if (CollectionUtils.isNotEmpty(mapList)) {
			pager.setDataList(mapList.stream().map(this::buildByMap).collect(Collectors.toList()));
		}
		final Integer totalCount = jdbcTemplate.queryForObject(String.format("select count(1) from %s", tableName),
				Integer.class);
		pager.setPage(PageHelper.buildPage(pageParameter, totalCount));
		return pager;
	}

	@Override
	public Boolean batchRemove(final List<String> ids, final String applicationName) {
		if (CollectionUtils.isEmpty(ids) || StringUtils.isBlank(applicationName)) {
			return Boolean.FALSE;
		}
		final String tableName = RepositoryPathUtils.buildDbTableName(applicationName);
		ids.stream().map(id -> buildDelSql(tableName, id)).forEach(jdbcTemplate::execute);
		return Boolean.TRUE;
	}

	@Override
	public Boolean updateRetry(final String id, final Integer retry, final String appName) {
		if (StringUtils.isBlank(id) || StringUtils.isBlank(appName) || Objects.isNull(retry)) {
			return false;
		}
		final String tableName = RepositoryPathUtils.buildDbTableName(appName);
		String sqlBuilder = String.format("update %s  set retried_count = %d,last_time= '%s' where trans_id =%s",
				tableName, retry, DateUtils.getCurrentDateTime(), id);
		jdbcTemplate.execute(sqlBuilder);
		return Boolean.TRUE;
	}

	private TccCompensationVO buildByMap(final Map<String, Object> map) {
		TccCompensationVO vo = new TccCompensationVO();
		vo.setTransId((String) map.get("trans_id"));
		vo.setRetriedCount((Integer) map.get("retried_count"));
		vo.setCreateTime(String.valueOf(map.get("create_time")));
		vo.setLastTime(String.valueOf(map.get("last_time")));
		vo.setVersion((Integer) map.get("version"));
		vo.setTargetClass((String) map.get("target_class"));
		vo.setTargetMethod((String) map.get("target_method"));
		vo.setConfirmMethod((String) map.get("confirm_method"));
		vo.setCancelMethod((String) map.get("cancel_method"));
		return vo;
	}

	public void setDbType(final String dbType) {
		this.dbType = DbTypeUtils.buildByDriverClassName(dbType);
	}

	private String buildPageSql(final String sql, final PageParameter pageParameter) {
		switch (dbType) {
		case CommonConstant.DB_MYSQL:
			return PageHelper.buildPageSqlForMysql(sql, pageParameter).toString();
		case CommonConstant.DB_ORACLE:
			return PageHelper.buildPageSqlForOracle(sql, pageParameter).toString();
		default:
			return "";
		}
	}

	private String buildDelSql(final String tableName, final String id) {
		return "DELETE FROM " + tableName + " WHERE trans_id=" + id;
	}
}