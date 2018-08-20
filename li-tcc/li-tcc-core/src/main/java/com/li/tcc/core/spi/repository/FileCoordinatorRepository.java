package com.li.tcc.core.spi.repository;

import com.google.common.collect.Lists;
import com.li.tcc.common.bean.adapter.CoordinatorRepositoryAdapter;
import com.li.tcc.common.bean.entity.TccTransaction;
import com.li.tcc.common.config.TccConfig;
import com.li.tcc.common.enums.RepositorySupportEnum;
import com.li.tcc.common.exception.TccException;
import com.li.tcc.common.exception.TccRuntimeException;
import com.li.tcc.common.serializer.ObjectSerializer;
import com.li.tcc.common.utils.FileUtils;
import com.li.tcc.common.utils.RepositoryConvertUtils;
import com.li.tcc.common.utils.RepositoryPathUtils;
import com.li.tcc.core.spi.CoordinatorRepository;

import java.io.File;
import java.io.FileInputStream;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * file impl
 * 
 * @author yuan.li
 */
public class FileCoordinatorRepository implements CoordinatorRepository {

	private static volatile boolean initialized;

	private String filePath;

	private ObjectSerializer serializer;

	@Override
	public void setSerializer(final ObjectSerializer serializer) {
		this.serializer = serializer;
	}

	@Override
	public int create(final TccTransaction tccTransaction) {
		writeFile(tccTransaction);
		return 1;
	}

	@Override
	public int remove(final String id) {
		String fullFileName = RepositoryPathUtils.getFullFileName(filePath, id);
		File file = new File(fullFileName);
		if (file.exists()) {
			file.delete();
		}
		return ROWS;
	}

	@Override
	public int update(final TccTransaction tccTransaction) throws TccRuntimeException {
		tccTransaction.setLastTime(new Date());
		tccTransaction.setVersion(tccTransaction.getVersion() + 1);
		tccTransaction.setRetriedCount(tccTransaction.getRetriedCount() + 1);
		try {
			writeFile(tccTransaction);
		} catch (Exception e) {
			throw new TccRuntimeException("更新数据异常！");
		}
		return 1;
	}

	@Override
	public int updateParticipant(final TccTransaction tccTransaction) {
		try {
			final String fullFileName = RepositoryPathUtils.getFullFileName(filePath, tccTransaction.getTransId());
			final File file = new File(fullFileName);
			final CoordinatorRepositoryAdapter adapter = readAdapter(file);
			if (Objects.nonNull(adapter)) {
				adapter.setContents(serializer.serialize(tccTransaction.getParticipants()));
			}
			FileUtils.writeFile(fullFileName, serializer.serialize(adapter));
		} catch (Exception e) {
			throw new TccRuntimeException("更新数据异常！");
		}
		return ROWS;
	}

	@Override
	public int updateStatus(final String id, final Integer status) {
		try {
			final String fullFileName = RepositoryPathUtils.getFullFileName(filePath, id);
			final File file = new File(fullFileName);
			final CoordinatorRepositoryAdapter adapter = readAdapter(file);
			if (Objects.nonNull(adapter)) {
				adapter.setStatus(status);
			}
			FileUtils.writeFile(fullFileName, serializer.serialize(adapter));
		} catch (Exception e) {
			throw new TccRuntimeException("更新数据异常！");
		}
		return ROWS;
	}

	@Override
	public TccTransaction findById(final String id) {
		String fullFileName = RepositoryPathUtils.getFullFileName(filePath, id);
		File file = new File(fullFileName);
		try {
			return readTransaction(file);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public List<TccTransaction> listAll() {
		List<TccTransaction> transactionRecoverList = Lists.newArrayList();
		File path = new File(filePath);
		File[] files = path.listFiles();
		if (files != null && files.length > 0) {
			for (File file : files) {
				try {
					TccTransaction transaction = readTransaction(file);
					transactionRecoverList.add(transaction);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return transactionRecoverList;
	}

	@Override
	public List<TccTransaction> listAllByDelay(final Date date) {
		final List<TccTransaction> tccTransactions = listAll();
		return tccTransactions.stream().filter(tccTransaction -> tccTransaction.getLastTime().compareTo(date) < 0)
				.collect(Collectors.toList());
	}

	@Override
	public void init(final String modelName, final TccConfig tccConfig) {
		filePath = RepositoryPathUtils.buildFilePath(modelName);
		File file = new File(filePath);
		if (!file.exists()) {
			file.getParentFile().mkdirs();
			file.mkdirs();
		}
	}

	@Override
	public String getScheme() {
		return RepositorySupportEnum.FILE.getSupport();
	}

	private void writeFile(final TccTransaction tccTransaction) {
		makeDir();
		String fileName = RepositoryPathUtils.getFullFileName(filePath, tccTransaction.getTransId());
		try {
			FileUtils.writeFile(fileName, RepositoryConvertUtils.convert(tccTransaction, serializer));
		} catch (TccException e) {
			e.printStackTrace();
		}
	}

	private TccTransaction readTransaction(final File file) throws Exception {
		try (FileInputStream fis = new FileInputStream(file)) {
			byte[] content = new byte[(int) file.length()];
			fis.read(content);
			return RepositoryConvertUtils.transformBean(content, serializer);
		}
	}

	private CoordinatorRepositoryAdapter readAdapter(final File file) throws Exception {
		try (FileInputStream fis = new FileInputStream(file)) {
			byte[] content = new byte[(int) file.length()];
			fis.read(content);
			return serializer.deSerialize(content, CoordinatorRepositoryAdapter.class);
		}
	}

	private void makeDir() {
		if (!initialized) {
			synchronized (FileCoordinatorRepository.class) {
				if (!initialized) {
					File rootPathFile = new File(filePath);
					if (!rootPathFile.exists()) {

						boolean result = rootPathFile.mkdir();

						if (!result) {
							throw new TccRuntimeException("cannot create root path, the path to create is:" + filePath);
						}
						initialized = true;
					} else if (!rootPathFile.isDirectory()) {
						throw new TccRuntimeException("rootPath is not directory");
					}
				}
			}
		}
	}
}
