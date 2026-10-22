/**
 * 为 src/api/axios.js 补充类型声明。
 *
 * axios.js 本身是 JS 文件，TypeScript 在 strict 下无法推断它的类型，
 * 于是每个「import axios from './axios'」都报 TS7016（隐式 any），
 * 全项目因此产生 17 处类型错误，且调用处拿不到任何补全与类型提示。
 *
 * 这里只声明「它是一个 Axios 实例」，不改动 axios.js 的任何运行时行为。
 * 拦截器（注入 token、错误提示、跳转登录）依旧在 axios.js 里生效。
 */
import type { AxiosInstance } from 'axios'

declare const http: AxiosInstance

export default http
