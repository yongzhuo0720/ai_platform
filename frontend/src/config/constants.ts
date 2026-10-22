/**
 * 全局常量
 * @description 集中管理散落在各处的「魔法数字」，避免业务逻辑里直接写字面量
 */

/**
 * 超级管理员角色 ID
 * @description 后端约定 rid === 99999 表示平台超级管理员。
 * 此前该值在前端 30+ 处硬编码为字面量 99999，一旦后端调整极易漏改，
 * 故统一收口到此处，配合 isSuperAdmin / useIsAdmin 使用。
 */
export const SUPER_ADMIN_RID = 99999;

/**
 * 判断给定角色 ID 是否为超级管理员
 * @param rid 角色 ID（可能来自 cookie / 接口，类型不定）
 * @returns 是超管返回 true，否则 false
 */
export const isSuperAdmin = (rid?: number | string | null): boolean =>
  Number(rid) === SUPER_ADMIN_RID;
