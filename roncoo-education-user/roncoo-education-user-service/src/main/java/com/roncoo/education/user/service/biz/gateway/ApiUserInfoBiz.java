package com.roncoo.education.user.service.biz.gateway;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.aliyuncs.exceptions.ClientException;
import com.roncoo.education.user.common.bean.bo.UserLoginCodeBO;
import com.roncoo.education.user.common.bean.bo.UserLoginPasswordBO;
import com.roncoo.education.user.common.bean.bo.UserRegisterBO;
import com.roncoo.education.user.common.bean.bo.UserSendCodeBO;
import com.roncoo.education.user.common.bean.dto.UserLoginDTO;
import com.roncoo.education.user.service.dao.PlatformDao;
import com.roncoo.education.user.service.dao.UserDao;
import com.roncoo.education.user.service.dao.UserExtDao;
import com.roncoo.education.user.service.dao.UserLogLoginDao;
import com.roncoo.education.user.service.dao.impl.mapper.entity.Platform;
import com.roncoo.education.user.service.dao.impl.mapper.entity.User;
import com.roncoo.education.user.service.dao.impl.mapper.entity.UserExt;
import com.roncoo.education.user.service.dao.impl.mapper.entity.UserLogLogin;
import com.roncoo.education.util.aliyun.AliyunSmsUtil;
import com.roncoo.education.util.base.BaseBiz;
import com.roncoo.education.util.base.Result;
import com.roncoo.education.util.enums.LoginStatusEnum;
import com.roncoo.education.util.enums.StatusIdEnum;
import com.roncoo.education.util.enums.UserTypeEnum;
import com.roncoo.education.util.tools.Constants;
import com.roncoo.education.util.tools.JWTUtil;
import com.roncoo.education.util.tools.NOUtil;
import com.roncoo.education.util.tools.StrUtil;
import com.xiaoleilu.hutool.crypto.DigestUtil;
import com.xiaoleilu.hutool.util.ObjectUtil;
import com.xiaoleilu.hutool.util.RandomUtil;

/**
 * 用户基本信息
 *
 * @author wujing
 */
@Component
public class ApiUserInfoBiz extends BaseBiz {

	@Autowired
	private PlatformDao platformDao;
	@Autowired
	private UserDao userDao;
	@Autowired
	private UserExtDao userExtDao;
	@Autowired
	private UserLogLoginDao userLogLoginDao;

	@Autowired
	private RedisTemplate<String, String> redisTemplate;

	@Transactional
	public Result<UserLoginDTO> register(UserRegisterBO userRegisterBO) {
		if (StringUtils.isEmpty(userRegisterBO.getMobile())) {
			return Result.error("手机号不能为空");
		}
		if (StringUtils.isEmpty(userRegisterBO.getPassword())) {
			return Result.error("密码不能为空");
		}
		if (StringUtils.isEmpty(userRegisterBO.getClientId())) {
			return Result.error("clientId不能为空");
		}

		// 密码校验
		if (!userRegisterBO.getPassword().equals(userRegisterBO.getRepassword())) {
			return Result.error("2次密码不一致");
		}

		Platform platform = platformDao.getByClientId(userRegisterBO.getClientId());
		if (null == platform) {
			return Result.error("该平台不存在");
		}
		if (!StatusIdEnum.YES.getCode().equals(platform.getStatusId())) {
			return Result.error("该平台状态异常，请联系管理员");
		}

		// 验证码校验
		String redisSmsCode = redisTemplate.opsForValue().get(platform.getClientId() + userRegisterBO.getMobile());
		if (StringUtils.isEmpty(redisSmsCode)) {
			return Result.error("请输入验证码");
		}
		if (!redisSmsCode.equals(userRegisterBO.getCode())) {
			return Result.error("验证码不正确，请重新输入");
		}

		// 手机号重复校验
		User user = userDao.getByMobile(userRegisterBO.getMobile());
		if (null != user) {
			return Result.error("该手机号已经注册，请更换手机号");
		}

		// 用户注册
		user = register(userRegisterBO.getMobile(), userRegisterBO.getPassword(), platform.getClientId());

		UserLoginDTO dto = new UserLoginDTO();
		dto.setUserNo(user.getUserNo());
		dto.setMobile(user.getMobile());
		dto.setToken(JWTUtil.create(user.getUserNo(), JWTUtil.DATE));
		return Result.success(dto);
	}

	public Result<UserLoginDTO> loginPassword(UserLoginPasswordBO userLoginPasswordBO) {
		if (StringUtils.isEmpty(userLoginPasswordBO.getClientId())) {
			return Result.error("clientId不能为空");
		}
		if (StringUtils.isEmpty(userLoginPasswordBO.getMobile())) {
			return Result.error("手机号不能为空");
		}
		if (StringUtils.isEmpty(userLoginPasswordBO.getPassword())) {
			return Result.error("密码不能为空");
		}
		Platform platform = platformDao.getByClientId(userLoginPasswordBO.getClientId());
		if (null == platform) {
			return Result.error("该平台不存在");
		}
		if (!StatusIdEnum.YES.getCode().equals(platform.getStatusId())) {
			return Result.error("该平台状态异常，请联系管理员");
		}

		// 密码错误次数校验

		// 用户校验
		User user = userDao.getByMobile(userLoginPasswordBO.getMobile());
		if (null == user) {
			return Result.error("账号或者密码不正确");
		}
		// 密码校验
		if (!DigestUtil.sha1Hex(user.getMobileSalt() + userLoginPasswordBO.getPassword()).equals(user.getMobilePsw())) {
			loginLog(user.getUserNo(), userLoginPasswordBO.getClientId(), LoginStatusEnum.FAIL, userLoginPasswordBO.getIp());
			// 放入缓存，错误次数+1
			return Result.error("账号或者密码不正确");
		}

		// 登录日志
		loginLog(user.getUserNo(), userLoginPasswordBO.getClientId(), LoginStatusEnum.SUCCESS, userLoginPasswordBO.getIp());

		UserLoginDTO dto = new UserLoginDTO();
		dto.setUserNo(user.getUserNo());
		dto.setMobile(user.getMobile());
		dto.setToken(JWTUtil.create(user.getUserNo(), JWTUtil.DATE));

		// 登录成功，存入缓存，单点登录使用
		redisTemplate.opsForValue().set(dto.getUserNo().toString(), dto.getToken(), 1, TimeUnit.DAYS);

		return Result.success(dto);
	}

	public Result<UserLoginDTO> loginCode(UserLoginCodeBO userLoginCodeBO) {
		if (StringUtils.isEmpty(userLoginCodeBO.getClientId())) {
			return Result.error("clientId不能为空");
		}
		if (StringUtils.isEmpty(userLoginCodeBO.getMobile())) {
			return Result.error("手机号码不能为空");
		}
		Platform platform = platformDao.getByClientId(userLoginCodeBO.getClientId());
		if (ObjectUtil.isNull(platform)) {
			return Result.error("该平台不存在");
		}
		if (!StatusIdEnum.YES.getCode().equals(platform.getStatusId())) {
			return Result.error("该平台状态异常，请联系管理员");
		}

		// 登录错误次数的校验

		// 验证码校验
		String redisSmsCode = redisTemplate.opsForValue().get(platform.getClientId() + userLoginCodeBO.getMobile());
		if (StringUtils.isEmpty(redisSmsCode)) {
			return Result.error("验证码已经过期，请重新获取");
		}

		User user = userDao.getByMobile(userLoginCodeBO.getMobile());
		if (null == user) {
			return Result.error("该用户不存在");
		}

		if (!redisSmsCode.equals(userLoginCodeBO.getCode())) {
			loginLog(user.getUserNo(), userLoginCodeBO.getClientId(), LoginStatusEnum.FAIL, userLoginCodeBO.getIp());
			// 缓存控制错误次数
			return Result.error("验证码不正确,重新输入");
		}

		// 清空缓存
		redisTemplate.delete(platform.getClientId() + userLoginCodeBO.getMobile());

		// 登录日志
		loginLog(user.getUserNo(), userLoginCodeBO.getClientId(), LoginStatusEnum.SUCCESS, userLoginCodeBO.getIp());

		UserLoginDTO dto = new UserLoginDTO();
		dto.setUserNo(user.getUserNo());
		dto.setMobile(user.getMobile());
		dto.setToken(JWTUtil.create(user.getUserNo(), JWTUtil.DATE));

		// 登录成功，存入缓存，单点登录使用
		redisTemplate.opsForValue().set(dto.getUserNo().toString(), dto.getToken(), 1, TimeUnit.DAYS);
		return Result.success(dto);
	}

	public Result<String> sendCode(UserSendCodeBO userSendCodeBO) {
		if (StringUtils.isEmpty(userSendCodeBO.getClientId())) {
			return Result.error("clientId不能为空");
		}
		if (!Pattern.compile(Constants.REGEX_MOBILE).matcher(userSendCodeBO.getMobile()).matches()) {
			return Result.error("手机号码格式不正确");
		}

		Platform platform = platformDao.getByClientId(userSendCodeBO.getClientId());
		if (ObjectUtil.isNull(platform)) {
			return Result.error("该平台不存在");
		}
		if (!StatusIdEnum.YES.getCode().equals(platform.getStatusId())) {
			return Result.error("该平台状态异常，请联系管理员");
		}

		// 校验发送次数

		// 获取模板
		String code = RandomUtil.randomNumbers(6);
		try {
			// 发送验证码
			AliyunSmsUtil.sendCode(userSendCodeBO.getMobile(), code);
			// 验证码存入缓存：5分钟有效
			redisTemplate.opsForValue().set(userSendCodeBO.getClientId() + userSendCodeBO.getMobile(), code, 5, TimeUnit.MINUTES);
			return Result.success("发送成功");
		} catch (ClientException e) {
			logger.error("发送失败，原因={}", e.getErrMsg());
			return Result.error("发送失败");
		}
	}

	private User register(String mobile, String password, String clientId) {
		// 用户基本信息
		User user = new User();
		user.setUserNo(NOUtil.getUserNo());
		user.setMobile(mobile);
		user.setMobileSalt(StrUtil.get32UUID());
		user.setMobilePsw(DigestUtil.sha1Hex(user.getMobileSalt() + password));
		user.setUserSource(clientId);
		userDao.save(user);

		// 用户其他信息
		UserExt userExt = new UserExt();
		userExt.setUserNo(user.getUserNo());
		userExt.setUserType(UserTypeEnum.USER.getCode());
		userExt.setMobile(user.getMobile());
		userExtDao.save(userExt);
		
		return user;
	}

	private void loginLog(Long userNo, String clientId, LoginStatusEnum status, String ip) {
		UserLogLogin record = new UserLogLogin();
		record.setUserNo(userNo);
		record.setClientId(clientId);
		record.setLoginStatus(status.getCode());
		record.setLoginIp(ip);
		userLogLoginDao.save(record);
	}

}
