import axios from 'axios';
import Cookies from 'js-cookie'
import { ElMessage } from 'element-plus'

const isAdmin = () => window.location.hash.startsWith('#/admin')

const _axios = axios.create({
    timeout: 30000,
    baseURL: '',
    withCredentials: true,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
    }
});

_axios.interceptors.request.use(config => {
  const prefix = isAdmin() ? 'admin_' : ''
  config.headers['x-access-token'] = Cookies.get(prefix + 'loginToken') || 'login'
  config.headers['account'] = Cookies.get(isAdmin() ? 'admin_uaccess' : 'uaccess')
  return config;
});

_axios.interceptors.response.use(
  response => {
    if (response.data.resp_code === 0 || response.data.resp_code === 200 || response.data.code === 200) {
    } else if (response.data.resp_code === 20102 || (response.data.resp_msg && response.data.resp_msg.indexOf('先登录') > -1)) {
      location.href = isAdmin() ? './#/admin/login' : './#/login'
    } else if (response.data.resp_msg) {
      ElMessage.error(response.data.resp_msg);
    }
    return Promise.resolve(response);
  },
  error => {
    // 注意：这里必须始终返回 rejected promise。
    // 原实现缺少 else 分支，超时/断网等「无 response」的场景会隐式 return undefined，
    // 使外层 await 解析为 undefined，调用方 `const { data } = await api.xxx()` 直接抛 TypeError。
    let message = '网络错误'

    if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    } else if (error.response?.status) {
      const status = error.response.status
      if (status === 401 || status === 403) {
        message = '登录已失效或没有权限'
        location.href = isAdmin() ? './#/admin/login' : './#/login'
      } else if (status === 404) {
        message = '接口不存在（404），请检查 Nginx 是否已配置该前缀的代理规则'
      } else if (status >= 500) {
        message = '服务异常，请稍后重试'
      }
    }

    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default _axios;
