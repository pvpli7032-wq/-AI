package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.mapper.SysWechatUserMapper;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.service.ISysWechatUserService;
import com.ruoyi.system.cps.attribution.InviteBindingService;

/**
 * 微信用户关联Service业务层处理
 * 
 * @author ruoyi
 * @date 2026-03-13
 */
@Service
public class SysWechatUserServiceImpl implements ISysWechatUserService 
{
    @Autowired
    private SysWechatUserMapper sysWechatUserMapper;

    @Autowired
    private InviteBindingService inviteBindingService;

    /**
     * 查询微信用户关联
     * 
     * @param id 微信用户关联主键
     * @return 微信用户关联
     */
    @Override
    public SysWechatUser selectSysWechatUserById(Long id)
    {
        return sysWechatUserMapper.selectSysWechatUserById(id);
    }

    /**
     * 根据openid查询微信用户
     * 
     * @param openid 微信openid
     * @return 微信用户关联
     */
    @Override
    public SysWechatUser selectByOpenid(String openid)
    {
        return sysWechatUserMapper.selectByOpenid(openid);
    }

    /**
     * 根据用户ID查询微信用户
     * 
     * @param userId 用户ID
     * @return 微信用户关联
     */
    @Override
    public SysWechatUser selectByUserId(Long userId)
    {
        return sysWechatUserMapper.selectByUserId(userId);
    }

    /**
     * 查询微信用户关联列表
     * 
     * @param sysWechatUser 微信用户关联
     * @return 微信用户关联
     */
    @Override
    public List<SysWechatUser> selectSysWechatUserList(SysWechatUser sysWechatUser)
    {
        return sysWechatUserMapper.selectSysWechatUserList(sysWechatUser);
    }

    /**
     * 新增微信用户关联
     * 
     * @param sysWechatUser 微信用户关联
     * @return 结果
     */
    @Override
    public int insertSysWechatUser(SysWechatUser sysWechatUser)
    {
        // Keep the existing creation entrance; it cannot accept a caller-supplied inviter ID.
        return inviteBindingService.registerFirstAuthorization(sysWechatUser, null);
    }

    @Override
    public int insertFirstAuthorizedUser(SysWechatUser sysWechatUser, String invitationCode)
    {
        return inviteBindingService.registerFirstAuthorization(sysWechatUser, invitationCode);
    }

    @Override
    public String ensureInvitationCode(Long userId)
    {
        return inviteBindingService.ensureInvitationCode(userId);
    }

    /**
     * 修改微信用户关联
     * 
     * @param sysWechatUser 微信用户关联
     * @return 结果
     */
    @Override
    public int updateSysWechatUser(SysWechatUser sysWechatUser)
    {
        sysWechatUser.setUpdateTime(DateUtils.getNowDate());
        // Profile refreshes often carry an old copy of this record. Never overwrite an audited binding.
        String code = sysWechatUser.getInvitationCode();
        Long inviter = sysWechatUser.getInviterUserId();
        sysWechatUser.setInvitationCode(null);
        sysWechatUser.setInviterUserId(null);
        try {
            return sysWechatUserMapper.updateSysWechatUser(sysWechatUser);
        } finally {
            sysWechatUser.setInvitationCode(code);
            sysWechatUser.setInviterUserId(inviter);
        }
    }

    /**
     * 批量删除微信用户关联
     * 
     * @param ids 需要删除的微信用户关联主键
     * @return 结果
     */
    @Override
    public int deleteSysWechatUserByIds(Long[] ids)
    {
        return sysWechatUserMapper.deleteSysWechatUserByIds(ids);
    }

    /**
     * 删除微信用户关联信息
     * 
     * @param id 微信用户关联主键
     * @return 结果
     */
    @Override
    public int deleteSysWechatUserById(Long id)
    {
        return sysWechatUserMapper.deleteSysWechatUserById(id);
    }
}
