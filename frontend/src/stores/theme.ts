import { defineStore } from 'pinia'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'app-theme'

export const useThemeStore = defineStore('theme', {
  state: () => ({
    theme: (localStorage.getItem(STORAGE_KEY) || 'dark') as Theme,
  }),
  actions: {
    setTheme(value: Theme) {
      this.theme = value
      this.apply()
    },
    apply() {
      document.documentElement.setAttribute('data-theme', this.theme)
      localStorage.setItem(STORAGE_KEY, this.theme)
    },
  },
})
