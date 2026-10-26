//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.aiproject.ai.permission.bean;

import blxt.qjava.autovalue.enumbean.JoinEnum;
import blxt.qjava.autovalue.inter.SelectLike;
import blxt.qjava.autovalue.inter.SelectLink;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.heneng.hnsp.business.user.bean.UserBaseDb;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
@SqlBean(
        value = "userbase",
        keyId = "uid",
        ignores = {"iid", "sid"}
)
@ApiModel(
        value = "用户对象",
        description = "用户基础信息"
)
@Data
public class MyUserBaseDb extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("用户uid")
    String uid;

    @SelectLike()
    @SqlColumn()
    @ApiModelProperty("用户账号,登录账号")
    String uaccess;

    /**
     * 用户密码（哈希后存储）。
     *
     * <p>双重防护，缺一不可：</p>
     * <ul>
     *   <li>{@code @SqlColumn(selectEnable = false)} —— 框架的 SELECT 永远不读这一列，
     *       查询结果里它天然是 null；</li>
     *   <li>{@code WRITE_ONLY} —— 只允许从请求体<b>反序列化进来</b>（创建用户、改密码要收密码），
     *       但绝不允许序列化到 HTTP 响应里。</li>
     * </ul>
     *
     * <p><b>为什么不用 @JsonIgnore</b>：它连反序列化一起挡掉，会让
     * {@code /myuser/create} 与 {@code /myuser/update} 收不到密码字段，
     * 建用户和改密码直接失效。这个是实测踩出来的坑。</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @SqlColumn(selectEnable = false)
    @ApiModelProperty("用户密码")
    String upwd;

    @SelectLike()
    @SqlColumn()
    @ApiModelProperty("用户名")
    String uname;

    @SqlColumn()
    @ApiModelProperty("是否已登录")
    Boolean login;

    @SqlColumn()
    @ApiModelProperty(value = "角色id", example = "00")
    Long rid;

    @SqlColumn()
    @ApiModelProperty("组织id")
    String gid;

    @SelectLink(
            joinTableB = "rolebean",
            joinKeyA = "rid",
            joinKeyB = "iid",
            joinColumB = "name",
            join = JoinEnum.PG_LEFT_OUTER_JOIN
    )
    @ApiModelProperty(value = "角色名称")
    public String rname;


    @SelectLink(
            joinTableB = "unit",
            joinKeyA = "gid",
            joinKeyB = "unitsno",
            joinColumB = "unitname",
            join = JoinEnum.PG_LEFT_OUTER_JOIN
    )
    @SqlColumn
    @ApiModelProperty("部门名称")
    String gname;

    @SelectLike()
    @SqlColumn()
    @ApiModelProperty("地点")
    String place;

    @SelectLike()
    @SqlColumn()
    @ApiModelProperty("描述")
    String description;

    @SqlColumn()
    @ApiModelProperty("岗位职称")
    String station;

    @SqlColumn()
    @ApiModelProperty("微信账号")
    String wechatid;

    @SqlColumn()
    @ApiModelProperty("头像")
    String ico;

    @SelectLike()
    @SqlColumn()
    @ApiModelProperty("手机号")
    String phone;

    @SqlColumn()
    @ApiModelProperty("邮箱")
    String email;

//    @SqlColumn
//    @SelectLink(joinTableB="userbase",
//            joinTableBrname = "userbase_2_",
//            joinKeyA = "lastuser", joinKeyB = "uid",
//            joinColumB = "uname",
//            join = JoinEnum.PG_LEFT_OUTER_JOIN)
//    @ApiModelProperty("作者名字, 创建项目时,需要带入")
//    String lastusername = "";



}
