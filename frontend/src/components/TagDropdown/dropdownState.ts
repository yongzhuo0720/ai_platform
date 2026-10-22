// 下拉框全局状态管理
class DropdownState {
  private currentOpenId: string | null = null
  private listeners: Set<(id: string | null) => void> = new Set()

  subscribe(callback: (id: string | null) => void) {
    this.listeners.add(callback)
    return () => {
      this.listeners.delete(callback)
    }
  }

  open(id: string) {
    this.currentOpenId = id
    this.notify(id)
  }

  close() {
    this.currentOpenId = null
    this.notify(null)
  }

  getCurrentOpenId(): string | null {
    return this.currentOpenId
  }

  private notify(id: string | null) {
    this.listeners.forEach(listener => listener(id))
  }
}

// 单例模式
export const dropdownState = new DropdownState()
