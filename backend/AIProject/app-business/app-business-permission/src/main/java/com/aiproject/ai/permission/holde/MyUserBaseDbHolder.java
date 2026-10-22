package com.aiproject.ai.permission.holde;

import blxt.qjava.utils.TimeHelper;
import com.heneng.hnsp.business.user.bean.UserBaseDb;
import com.heneng.hnsp.business.user.utils.UpwdTools;
import org.springframework.beans.factory.annotation.Autowired;
import com.heneng.hnsp.common.croe.model.Result;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import com.heneng.hnsp.common.db.utils.PageRequest;
import java.lang.String;
import org.springframework.stereotype.Service;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.mapper.MyUserBaseDbMapper;


/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 * @author heneng
 * @date 2026/05/08 15:13:49
 */
@Slf4j
@Service
public  class MyUserBaseDbHolder  { 
        /**
     * 数据库Mapper.
     */ 
    @Autowired
    MyUserBaseDbMapper<MyUserBaseDb> userbaseMapper = null;

    @Autowired
    UpwdTools upwdTools;

    public boolean exists(MyUserBaseDb bean) {
        return userbaseMapper.count(bean) > 0;
    }
    /**
     * 添加
     * @param bean
     * @return
     */

    public Result<String> create(String uid, MyUserBaseDb... bean){
        /*
         * 越权防护：创建账号必须由管理员执行。
         * 修复前该接口只校验「已登录」，任意用户都能
         *   POST /ai/myuser/create {"bean":{"uaccess":"x","upwd":"p","rid":99999}}
         * 直接造出一个超级管理员账号再正常登录 —— 权限模型被整体绕过。
         * 全仓库没有其它注册入口（UserLoginController 只有登录/登出/改密），
         * 用户管理页面也只对超级管理员开放，因此这里收紧不影响正常流程。
         */
        if (!isAdmin(uid)) {
            return Result.failed("仅管理员可创建用户");
        }
        for(int i = 0; i < bean.length; i++){
            if (bean[i] == null){
                continue;
            }
            MyUserBaseDb userBase = new MyUserBaseDb();
            userBase.setUaccess(bean[i].getUaccess());
            if(exists(userBase)){
                return Result.failed("账号已存在");
            }
            // 计算密码
            if(bean[i].getUpwd() != null){
                bean[i].setUpwd(upwdTools.makePwdMD5(bean[i].getUpwd()));
            }
            // 辅助自动计算uid
            if(bean[i].getUid() == null) {
                bean[i].setUid(String.format("%d%s", TimeHelper.getTime(), bean[i].getUaccess()));
            }

            bean[i].setLastuser(uid);
        }

        int size = userbaseMapper.install(Arrays.asList(bean));
        if( size >= 1){
            return Result.succeed("创建成功:" + size);
        }

        return Result.failed("创建失败");
    }


    /**
     * 删除用户。
     * <p>
     * 修复前：只校验「已登录」，任意用户都能
     * {@code POST /ai/myuser/delete {"bean":{"uid":"<任意 uid>"}}}，
     * 包括删掉超级管理员 —— 账号行就是登录身份，属于完全破坏性操作。
     * 现在限制为管理员，并禁止删除自己（避免误操作把唯一管理员删掉）。
     */
    public Result<String> delete(String uid, MyUserBaseDb bean){
        if (!isAdmin(uid)) {
            return Result.failed("仅管理员可删除用户");
        }
        if (uid.equals(bean.getUid())) {
            return Result.failed("不能删除当前登录账号");
        }
        int res = userbaseMapper.delete(bean);
        if (res > 0) {
            return Result.succeed("删除成功");
        }
        return Result.failed("删除失败");
    }

    /**
     * 更新
     * @param data
     * @return
     */

    public Result<String> update(String uid, MyUserBaseDb data, MyUserBaseDb where){
        /*
         * 越权防护。
         * 原有校验只比较 data 与 where 内部的 uid / uaccess 是否一致，
         * 当请求体为 { bean:{upwd:"xxx"}, where:{uid:"<他人uid>"} } 时，
         * data.getUid() 与 data.getUaccess() 均为 null，两个校验被全部跳过，
         * 于是任意登录用户都能修改他人密码（前端「修改密码」正好就是这种请求形态）。
         * 这里改为：非管理员只能更新自己，条件里的 uid 强制收敛为登录 uid。
         */
        boolean admin = isAdmin(uid);
        if (!admin) {
            if (uid == null) {
                return Result.failed("请先登录");
            }
            if (where.getUid() != null && !uid.equals(where.getUid())) {
                return Result.failed("禁止修改其他用户的信息");
            }
            where.setUid(uid);
        }
        if(data.getUid() != null){
            if(!data.getUid().equals(where.getUid())){
                return Result.failed("禁止更改账户ID");
            }
        }
        if(data.getUaccess() != null){
            if(!data.getUaccess().equals(where.getUaccess())){
                return Result.failed("禁止更改账户ID");
            }
        }
        /*
         * 提权防护：rid（角色）与 gid（部门）是权限模型的两根支柱，
         * 但原实现完全没有校验，导致
         *   POST /ai/myuser/update {"bean":{"rid":99999},"where":{"uid":"<自己的uid>"}}
         * 就能自我提权为超级管理员，随后所有 checkAdmin 网关（能力广场 / 数据集 /
         * 模型 / 应用 / Dify 配置）全部放行；同理改写 gid 可绕过知识库、能力广场的
         * 部门黑名单（partial_invisible）。
         * 管理员在「用户管理」页面确实需要改这两个字段，所以只对非管理员禁止，
         * 并且与 DB 现值比较：值没变就放行，避免「表单原样回传」被误判为提权。
         */
        MyUserBaseDb existing = queryUserByUid(where.getUid());
        if (existing == null) {
            return Result.failed("用户不存在");
        }
        if (!admin) {
            Long oldRid = existing.getRid();
            Long newRid = data.getRid();
            if (newRid != null && !newRid.equals(oldRid)) {
                return Result.failed("禁止自行变更角色");
            }
            String oldGid = existing.getGid() == null ? "" : existing.getGid();
            String newGid = data.getGid() == null ? "" : data.getGid();
            if (!newGid.equals(oldGid)) {
                return Result.failed("禁止自行变更所属部门");
            }
            // 非管理员不允许走这个接口改密（改密应走专门的校验旧密码流程）
            if (data.getUpwd() != null) {
                return Result.failed("请通过修改密码功能变更密码");
            }
        }
        // 计算密码
        if(data.getUpwd() != null){
            data.setUpwd(upwdTools.makePwdMD5(data.getUpwd()));
        }
        data.setLastuser(uid);
        int res = userbaseMapper.update(data, where);
        if(res > 0){
            return Result.succeed("更新成功");
        }
        return Result.failed("更新失败");
    }

    /**
     * 分页查询 - 通过sql多次查询分页
     *
     * @param uid          调用者 uid，用于权限判定（非管理员走脱敏返回）
     * @param bean          用户信息
     * @param pageRequest   分页信息
     * @return
     */
    public Result<PageResult<MyUserBaseDb>> querys(String uid, MyUserBaseDb bean, PageRequest pageRequest){
        // 分页查询
        Result<PageResult<MyUserBaseDb>> result = userbaseMapper.selectPageLike(pageRequest, bean);

        // 非管理员脱敏返回。
        //
        // 背景：这个接口原本只校验登录，任意登录用户可以遍历全量用户的
        // 手机 / 邮箱 / 微信号 / 角色 / 部门，属于信息泄露。
        //
        // 但不能简单加超管校验拦住 —— 数据集列表页等普通页面也要用它拉「用户下拉选项」
        // （DatasetList/index.ts:98 拉 pageSize=1000 的全量用户），
        // 拦掉会让那些页面的用户选择器失效。
        // 所以按调用者身份区分：超管看全量，其他人只拿 id/账号/姓名，敏感字段清空。
        if (!isAdmin(uid) && result != null && result.getDatas() != null
                && result.getDatas().getContent() != null) {
            for (MyUserBaseDb item : result.getDatas().getContent()) {
                maskSensitiveFields(item);
            }
        }
        return result;
    }

    /** 清掉非管理员不该看到的字段 */
    private void maskSensitiveFields(MyUserBaseDb item) {
        if (item == null) {
            return;
        }
        item.setPhone(null);
        item.setEmail(null);
        item.setWechatid(null);
        item.setUpwd(null);
    }
    public MyUserBaseDb queryUserByUid(String uid){
       if (uid == null) {
           return null;
       }
       MyUserBaseDb myUserBaseDb=(MyUserBaseDb) userbaseMapper.selectById(uid);
       return  myUserBaseDb;
    }

    /**
     * 判断是否为超级管理员（rid=99999）。
     * 用户不存在或 rid 为空时一律按非管理员处理，不抛 NPE。
     * <p>
     * 供各业务 Holder 复用：能力广场 / 数据集仓库 / 模型仓库 / 应用构建 / Dify 配置
     * 等「平台级目录」在前端均以 rid==99999 控制增删改按钮，
     * 后端需要在写接口上做同样的判定，否则就是「只有前端鉴权」。
     */
    public boolean isAdmin(String uid){
        MyUserBaseDb self = queryUserByUid(uid);
        return self != null && self.getRid() != null && self.getRid() == 99999L;
    }



}
