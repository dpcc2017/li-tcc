package com.li.tcc.core.schedule;

import com.google.common.collect.Lists;
import com.li.tcc.common.annotation.TccPatternEnum;
import com.li.tcc.common.bean.context.TccTransactionContext;
import com.li.tcc.common.bean.entity.Participant;
import com.li.tcc.common.bean.entity.TccInvocation;
import com.li.tcc.common.bean.entity.TccTransaction;
import com.li.tcc.common.config.TccConfig;
import com.li.tcc.common.enums.TccActionEnum;
import com.li.tcc.common.enums.TccRoleEnum;
import com.li.tcc.common.utils.LogUtil;
import com.li.tcc.core.concurrent.threadlocal.TransactionContextLocal;
import com.li.tcc.core.concurrent.threadpool.LiThreadFactory;
import com.li.tcc.core.helper.SpringBeanUtils;
import com.li.tcc.core.service.executor.LiTransactionExecutor;
import com.li.tcc.core.spi.CoordinatorRepository;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * this is scheduled execute transaction log
 * 
 * @author yuan.li
 */
public class ScheduledService {

	/**
	 * logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledService.class);

	private ScheduledExecutorService scheduledExecutorService;

	private TccConfig tccConfig;

	private CoordinatorRepository coordinatorRepository;

	public ScheduledService(final TccConfig tccConfig, final CoordinatorRepository coordinatorRepository) {
		this.tccConfig = tccConfig;
		this.coordinatorRepository = coordinatorRepository;
		this.scheduledExecutorService = new ScheduledThreadPoolExecutor(1, LiThreadFactory.create("tccRollBackService",
				true));
	}

	/**
	 * if have some exception by schedule execute tcc transaction log
	 */
	public void scheduledRollBack() {
		scheduledExecutorService.scheduleWithFixedDelay(() -> {
			LogUtil.debug(LOGGER, "rollback execute delayTime:{}", () -> tccConfig.getScheduledDelay());
			try {
				final List<TccTransaction> tccTransactions = coordinatorRepository.listAllByDelay(acquireData());
				if (CollectionUtils.isEmpty(tccTransactions)) {
					return;
				}
				for (TccTransaction tccTransaction : tccTransactions) {
					// 如果try未执行完成，那么就不进行补偿 （防止在try阶段的各种异常情况）
				if (tccTransaction.getRole() == TccRoleEnum.PROVIDER.getCode()
						&& tccTransaction.getStatus() == TccActionEnum.PRE_TRY.getCode()) {
					continue;
				}
				if (tccTransaction.getRetriedCount() > tccConfig.getRetryMax()) {
					LogUtil.error(LOGGER, "此事务超过了最大重试次数，不再进行重试：{}", () -> tccTransaction);
					continue;
				}
				if (Objects.equals(tccTransaction.getPattern(), TccPatternEnum.CC.getCode())
						&& tccTransaction.getStatus() == TccActionEnum.TRYING.getCode()) {
					continue;
				}
				// 如果事务角色是提供者的话，并且在重试的次数范围类是不能执行的，只能由发起者执行
				if (tccTransaction.getRole() == TccRoleEnum.PROVIDER.getCode()
						&& (tccTransaction.getCreateTime().getTime() + tccConfig.getRetryMax()
								* tccConfig.getRecoverDelayTime() * 1000 > System.currentTimeMillis())) {
					continue;
				}
				try {
					// 先更新数据，然后执行
					tccTransaction.setRetriedCount(tccTransaction.getRetriedCount() + 1);
					final int rows = coordinatorRepository.update(tccTransaction);
					// 判断当rows>0 才执行，为了防止业务方为集群模式时候的并发
					if (rows > 0) {
						// 如果是以下3种状态
						if (tccTransaction.getStatus() == TccActionEnum.TRYING.getCode()
								|| tccTransaction.getStatus() == TccActionEnum.PRE_TRY.getCode()
								|| tccTransaction.getStatus() == TccActionEnum.CANCELING.getCode()) {
							LiTransactionExecutor.instance().set(tccTransaction);
							cancel(tccTransaction);
						} else if (tccTransaction.getStatus() == TccActionEnum.CONFIRMING.getCode()) {
							// 执行confirm操作
							LiTransactionExecutor.instance().set(tccTransaction);
							confirm(tccTransaction);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					LogUtil.error(LOGGER, "执行事务补偿异常:{}", e::getMessage);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}, 30, tccConfig.getScheduledDelay(), TimeUnit.SECONDS);

	}

	private void cancel(final TccTransaction tccTransaction) {
		final List<Participant> participants = tccTransaction.getParticipants();
		List<Participant> failList = Lists.newArrayListWithCapacity(participants.size());
		boolean success = true;
		if (CollectionUtils.isNotEmpty(participants)) {
			for (Participant participant : participants) {
				try {
					TccTransactionContext context = new TccTransactionContext();
					context.setAction(TccActionEnum.CANCELING.getCode());
					context.setTransId(tccTransaction.getTransId());
					TransactionContextLocal.getInstance().set(context);
					executeCoordinator(participant.getCancelTccInvocation());
				} catch (Exception e) {
					LogUtil.error(LOGGER, "执行cancel方法异常:{}", () -> e);
					success = false;
					failList.add(participant);
				}
			}
			executeHandler(success, tccTransaction, failList);
		}

	}

	private void confirm(final TccTransaction tccTransaction) {
		final List<Participant> participants = tccTransaction.getParticipants();
		List<Participant> failList = Lists.newArrayListWithCapacity(participants.size());
		boolean success = true;
		if (CollectionUtils.isNotEmpty(participants)) {
			for (Participant participant : participants) {
				try {
					TccTransactionContext context = new TccTransactionContext();
					context.setAction(TccActionEnum.CONFIRMING.getCode());
					context.setTransId(tccTransaction.getTransId());
					TransactionContextLocal.getInstance().set(context);

					executeCoordinator(participant.getConfirmTccInvocation());
				} catch (Exception e) {
					LogUtil.error(LOGGER, "执行confirm方法异常:{}", () -> e);
					success = false;
					failList.add(participant);
				}
			}
			executeHandler(success, tccTransaction, failList);
		}
	}

	private void executeHandler(final boolean success, final TccTransaction currentTransaction,
			final List<Participant> failList) {
		if (success) {
			coordinatorRepository.remove(currentTransaction.getTransId());
		} else {
			currentTransaction.setParticipants(failList);
			coordinatorRepository.updateParticipant(currentTransaction);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void executeCoordinator(final TccInvocation tccInvocation) throws Exception {
		if (Objects.nonNull(tccInvocation)) {
			final Class clazz = tccInvocation.getTargetClass();
			final String method = tccInvocation.getMethodName();
			final Object[] args = tccInvocation.getArgs();
			final Class[] parameterTypes = tccInvocation.getParameterTypes();
			final Object bean = SpringBeanUtils.getInstance().getBean(clazz);
			MethodUtils.invokeMethod(bean, method, args, parameterTypes);
			LogUtil.debug(LOGGER, "执行本地协调事务:{}",
					() -> tccInvocation.getTargetClass() + ":" + tccInvocation.getMethodName());
		}
	}

	private Date acquireData() {
		return new Date(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
				- (tccConfig.getRecoverDelayTime() * 1000));
	}

}
