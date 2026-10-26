// 动态加载的文档解析器：无官方类型的入口声明
declare module 'pdfjs-dist/legacy/build/pdf.min.js' {
  export const GlobalWorkerOptions: { workerSrc: string }
  export function getDocument(src: { data: ArrayBuffer }): {
    promise: Promise<{
      numPages: number
      getPage(page: number): Promise<{
        getTextContent(): Promise<{ items: Array<{ str?: string }> }>
      }>
    }>
  }
}
declare module 'mammoth/mammoth.browser' {
  export function extractRawText(input: {
    arrayBuffer: ArrayBuffer
  }): Promise<{ value: string; messages: unknown[] }>
}
