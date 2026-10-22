/**
 * 超管身份判定 composable
 * @description 统一主站「是否超级管理员」的判定逻辑（原多处重复写
 * `parseInt(Cookies.get('rid') || '0') === 99999`）。
 *
 * 注意：后台管理台(Admin 模块)使用不同的 cookie（admin_rid / admin_access），
 * 那边请用 isSuperAdmin(Cookies.get('admin_rid')) || Cookies.get('admin_access') === '1'。
 */

import { computed, type ComputedRef } from 'vue';
import Cookies from 'js-cookie';
import { isSuperAdmin } from '@/config/constants';

/** 主站超管判定：登录后 rid cookie === SUPER_ADMIN_RID */
export function useIsAdmin(): ComputedRef<boolean> {
  return computed(() => isSuperAdmin(Cookies.get('rid')));
}
