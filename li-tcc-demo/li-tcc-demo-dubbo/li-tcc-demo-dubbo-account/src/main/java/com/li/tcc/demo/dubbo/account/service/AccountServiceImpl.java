package com.li.tcc.demo.dubbo.account.service;

import com.li.tcc.common.annotation.Tcc;
import com.li.tcc.demo.dubbo.account.api.dto.AccountDTO;
import com.li.tcc.demo.dubbo.account.api.dto.AccountNestedDTO;
import com.li.tcc.demo.dubbo.account.api.entity.AccountDO;
import com.li.tcc.demo.dubbo.account.api.service.AccountService;
import com.li.tcc.demo.dubbo.account.mapper.AccountMapper;
import com.li.tcc.demo.dubbo.inventory.api.dto.InventoryDTO;
import com.li.tcc.demo.dubbo.inventory.api.service.InventoryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * @author yuan.li
 */
@Service("accountService")
public class AccountServiceImpl implements AccountService {

	/**
	 * logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(AccountServiceImpl.class);

	private final AccountMapper accountMapper;

	@Autowired(required = false)
	private InventoryService inventoryService;

	@Autowired(required = false)
	public AccountServiceImpl(AccountMapper accountMapper) {
		this.accountMapper = accountMapper;
	}

	/**
	 * 扣款支付
	 *
	 * @param accountDTO
	 *            参数dto
	 * @return true
	 */
	@Override
	@Tcc(confirmMethod = "confirm", cancelMethod = "cancel")
	@Transactional
	public boolean payment(AccountDTO accountDTO) {
		final AccountDO accountDO = accountMapper.findByUserId(accountDTO.getUserId());
		accountDO.setBalance(accountDO.getBalance().subtract(accountDTO.getAmount()));
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().add(accountDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.update(accountDO);
		return Boolean.TRUE;
	}

	/**
	 * 扣款支付
	 *
	 * @param accountNestedDTO
	 *            参数dto
	 * @return true
	 */
	@Override
	@Tcc(confirmMethod = "confirmNested", cancelMethod = "cancelNested")
	@Transactional
	public boolean paymentWithNested(AccountNestedDTO accountNestedDTO) {
		final AccountDO accountDO = accountMapper.findByUserId(accountNestedDTO.getUserId());
		accountDO.setBalance(accountDO.getBalance().subtract(accountNestedDTO.getAmount()));
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().add(accountNestedDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.update(accountDO);

		InventoryDTO inventoryDTO = new InventoryDTO();

		inventoryDTO.setCount(accountNestedDTO.getCount());
		inventoryDTO.setProductId(accountNestedDTO.getProductId());
		inventoryService.decrease(inventoryDTO);
		return Boolean.TRUE;
	}

	/**
	 * 获取用户账户信息
	 *
	 * @param userId
	 *            用户id
	 * @return AccountDO
	 */
	@Override
	public AccountDO findByUserId(String userId) {
		return accountMapper.findByUserId(userId);
	}

	@Transactional
	public boolean confirmNested(AccountNestedDTO accountNestedDTO) {
		LOGGER.debug("============dubbo tcc 执行确认付款接口===============");
		final AccountDO accountDO = accountMapper.findByUserId(accountNestedDTO.getUserId());
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().subtract(accountNestedDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.confirm(accountDO);
		return Boolean.TRUE;
	}

	@Transactional
	public boolean cancelNested(AccountNestedDTO accountNestedDTO) {
		LOGGER.debug("============ dubbo tcc 执行取消付款接口===============");
		final AccountDO accountDO = accountMapper.findByUserId(accountNestedDTO.getUserId());
		accountDO.setBalance(accountDO.getBalance().add(accountNestedDTO.getAmount()));
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().subtract(accountNestedDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.cancel(accountDO);
		return Boolean.TRUE;
	}

	@Transactional
	public boolean confirm(AccountDTO accountDTO) {
		LOGGER.debug("============dubbo tcc 执行确认付款接口===============");
		final AccountDO accountDO = accountMapper.findByUserId(accountDTO.getUserId());
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().subtract(accountDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.confirm(accountDO);
		return Boolean.TRUE;
	}

	@Transactional
	public boolean cancel(AccountDTO accountDTO) {
		LOGGER.debug("============ dubbo tcc 执行取消付款接口===============");
		final AccountDO accountDO = accountMapper.findByUserId(accountDTO.getUserId());
		accountDO.setBalance(accountDO.getBalance().add(accountDTO.getAmount()));
		accountDO.setFreezeAmount(accountDO.getFreezeAmount().subtract(accountDTO.getAmount()));
		accountDO.setUpdateTime(new Date());
		accountMapper.cancel(accountDO);
		return Boolean.TRUE;
	}
}
