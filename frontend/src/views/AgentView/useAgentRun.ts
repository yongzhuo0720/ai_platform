import { onBeforeUnmount, reactive } from 'vue'
import { streamAgentRun, type AgentConfig, type AgentRunDetail, type AgentStep } from '@/api/agent'
import { errorText, mergeSteps } from './agentPresentation'

export function useAgentRun(options: {
  selected: () => AgentConfig | undefined
  canStart: () => boolean
  onDone: () => void
}) {
  const live = reactive({
    question: '',
    submittedQuestion: '',
    runKey: '',
    steps: [] as AgentStep[],
    detail: null as AgentRunDetail | null,
    receiving: false,
    phase: 'idle' as 'idle' | 'connecting' | 'receiving' | 'done' | 'interrupted' | 'stopped',
    notice: '',
    errorMessage: '',
  })
  let alive = true
  let generation = 0
  let stopListening: (() => void) | undefined

  function release() {
    const stop = stopListening
    stopListening = undefined
    stop?.()
  }

  function reset() {
    generation++
    release()
    Object.assign(live, {
      question: '', submittedQuestion: '', runKey: '', steps: [], detail: null,
      receiving: false, phase: 'idle', notice: '', errorMessage: '',
    })
  }

  function start() {
    const agent = options.selected()
    const question = live.question.trim()
    if (!alive || live.receiving || !agent || !question || !options.canStart()) return
    const version = ++generation
    release()
    Object.assign(live, {
      submittedQuestion: question, runKey: '', steps: [], detail: null,
      receiving: true, phase: 'connecting', notice: '', errorMessage: '',
    })
    const current = () => alive && generation === version && live.receiving
    const fail = (message: string) => {
      if (!current()) return
      live.receiving = false
      live.phase = 'interrupted'
      live.errorMessage = message || '实时连接中断'
      live.notice = '已保留收到的步骤，请到运行历史刷新查看最终结果；不会自动重发请求。'
      release()
    }
    try {
      const stop = streamAgentRun({ agentKey: agent.agentKey, question }, {
        onStart(runKey) {
          if (!current()) return
          live.runKey = runKey
          live.phase = 'receiving'
        },
        onStep(step) {
          if (!current() || (live.runKey && step.runKey !== live.runKey)) return
          live.runKey = step.runKey
          live.phase = 'receiving'
          live.steps = mergeSteps(live.steps, [step])
        },
        onDone(detail) {
          if (!current()) return
          if (detail.run.agentIid !== agent.iid || (live.runKey && detail.run.runKey !== live.runKey)) {
            fail('运行详情与当前 Agent 不匹配，请到运行历史核实结果')
            return
          }
          live.runKey = detail.run.runKey
          live.steps = mergeSteps(live.steps, detail.steps)
          live.detail = { run: detail.run, steps: live.steps }
          live.receiving = false
          live.phase = 'done'
          release()
          options.onDone()
        },
        onError: fail,
      })
      // 也覆盖 stream 在返回停止函数前同步报错/完成的情况。
      if (current()) stopListening = stop
      else stop()
    } catch (error) {
      fail(errorText(error, '无法建立实时连接'))
    }
  }

  function stop() {
    if (!live.receiving) return
    generation++
    live.receiving = false
    live.phase = 'stopped'
    live.notice = '已停止接收并保留现有步骤。这不会立即中断正在进行的模型调用，后台可能继续执行并消耗额度；请到运行历史刷新查看结果。'
    release()
  }

  onBeforeUnmount(() => {
    alive = false
    generation++
    release()
  })

  return { live, start, stop, reset }
}
