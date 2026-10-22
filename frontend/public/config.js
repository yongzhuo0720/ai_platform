/**
 * 遗留文件，当前无任何代码引用（见 frontend/AI 平台前端部署文档.md）。
 *
 * 这里原先硬编码了一个 Dify 的应用密钥，属于凭据泄露，已移除。
 * Dify 的地址与密钥现在统一由「后端数据库 → /ai/api/difyConfigBean/query」下发，
 * 本地开发可用 frontend/.env.local 覆盖（见 frontend/.env.example）。
 *
 * 保留本文件仅为兼容可能的外部脚本引用，请勿再往这里填写任何密钥。
 */
const CONFIG = {
  apikey: '',
}
