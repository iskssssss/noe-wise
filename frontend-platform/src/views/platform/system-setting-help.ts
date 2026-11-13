import type { SystemSettingField } from '../../api'

const descriptions: Record<string, Record<string, string>> = {
  calls: {
    llm_timeout_seconds: '前台辅助模型请求的超时时间，例如摘要等辅助处理。',
    chat_timeout_seconds: '每次对话模型调用的超时时间。',
    http_tool_timeout_seconds: '每次 HTTP 工具请求的超时时间。',
    http_tool_max_response_bytes: 'HTTP 工具响应体的资源保护上限，超限响应会被拒绝。',
    external_retry_attempts:
      '包含首次调用在内的最大尝试次数；填写 1 表示不重试。是否重试还取决于错误类型和调用语义。',
    external_retry_backoff_milliseconds: '外部调用首次重试前的等待时间。'
  },
  memory: {
    session_ttl_minutes: 'Redis 会话热数据的保留时间，不等于持久会话历史的保留周期。',
    profile_summary_idle_minutes: '会话空闲达到该时间后可进入画像摘要处理。',
    incremental_merge: '允许近期摘要达到窗口后增量合并到用户画像。',
    max_raw_summaries: '画像维护时保留和参与合并的近期原始摘要窗口。',
    casual_threshold: '闲聊消息累计达到此数量后触发相应摘要处理。',
    casual_counter_ttl_days: '闲聊累计消息计数的缓存保留时间。'
  },
  knowledge: {
    chunk_size: '新知识库使用的默认文档分段长度。',
    chunk_overlap: '新知识库相邻分段默认重叠的字符数，必须小于默认分段长度。',
    max_upload_mb: '单个知识文档允许上传的最大文件大小。',
    max_chunks_per_document: '单个知识文档可生成的最大分块数量。',
    default_indexing_mode: '新知识库的默认索引方式：高质量索引或经济索引。',
    default_retrieval_mode: '新知识库的默认检索方式：语义检索、全文检索或混合检索。',
    default_top_k: '新知识库默认返回的检索结果数量。',
    default_score_threshold: '新知识库默认的相关度过滤阈值，是否过滤取决于知识库的阈值启用设置。',
    default_vector_weight: '新知识库混合检索中的默认向量侧权重，其余权重归全文侧。'
  },
  sandbox: {
    enabled: '允许代码执行工具使用沙箱；还需部署可用的沙箱服务。',
    execution_timeout_seconds: '单次代码执行的超时时间，不是整个任务的活跃时长额度。',
    workspace_retention_hours: '沙箱工作区的保留时间，到期后由清理任务回收。',
    output_capture_bytes: '单次代码执行可采集的输出大小上限。',
    max_artifact_files: '单次代码执行允许收集的产物文件数量上限。',
    max_concurrent_executions: '沙箱同时执行代码的数量上限，用于控制资源负载。',
    memory_limit: '沙箱容器的内存资源上限；填写正整数加 k、m 或 g，例如 256m。',
    cpu_limit: '沙箱容器可使用的 CPU 核数上限。',
    pids_limit: '沙箱容器内允许的进程数量上限。'
  },
  artifacts: {
    retention_days: '运行产物的保留时间，到期后由产物清理任务回收。',
    transfer_timeout_seconds: '单次产物传输的超时时间。',
    max_files: '单份产物允许包含的文件数量上限。'
  },
  rate_limits: {
    requests_per_minute_default: '默认访问限流额度，单位为每分钟请求次数。'
  },
  billing: {
    exchange_rate_enabled: '启用外部汇率查询；启用时必须配置服务地址。',
    exchange_rate_endpoint: '外部汇率 API 地址；关闭服务时可留空。',
    exchange_rate_api_key: '用于访问外部汇率服务的凭据。',
    exchange_rate_api_key_header: '发送汇率服务凭据时使用的 HTTP 请求头名称。',
    exchange_rate_connect_timeout_seconds: '建立汇率服务连接的超时时间。',
    exchange_rate_read_timeout_seconds: '读取汇率服务响应的超时时间。',
    usage_reconciliation_enabled: '启用外部用量对账；启用时必须配置服务地址。',
    usage_reconciliation_endpoint: '外部用量对账 API 地址；关闭服务时可留空。',
    usage_reconciliation_api_key: '用于访问外部用量对账服务的凭据。',
    usage_reconciliation_api_key_header: '发送对账服务凭据时使用的 HTTP 请求头名称。',
    usage_reconciliation_connect_timeout_seconds: '建立对账服务连接的超时时间。',
    usage_reconciliation_read_timeout_seconds: '读取对账服务响应的超时时间。'
  },
  prompts: {
    agent_decision_policy:
      '用于指导 Agent 决策的公共提示词。保留现有业务约束，不引入未声明的模板占位符。',
    chat_summary_prompt: '用于生成会话画像摘要的提示词，说明需要提取的信息和结果格式。',
    profile_merge_prompt: '用于将近期摘要合并到用户画像的提示词，说明合并与冲突处理要求。'
  }
}

export function systemSettingHelp(group: string, field: SystemSettingField) {
  const budget =
    group === 'task_budget'
      ? field.key.endsWith('_business_models')
        ? '整个任务的业务模型调用次数上限；辅助模型独立计次。'
        : field.key.endsWith('_tools')
          ? '整个任务共享的工具调用次数上限。'
          : field.key.endsWith('_knowledge')
            ? '整个任务共享的知识检索次数上限。'
            : field.key.endsWith('_total_tokens')
              ? '整个任务累计输入与输出的总 Tokens 上限，属于资源额度，不等同于精确货币成本。'
              : '整个任务的活跃执行时长上限；暂停期间不计时，最多七天。'
      : undefined
  const parts = [budget ?? descriptions[group]?.[field.key]]
  if (field.unit) parts.push(`单位：${field.unit}。`)
  if (
    field.minimum !== null &&
    field.minimum !== undefined &&
    field.maximum !== null &&
    field.maximum !== undefined
  )
    parts.push(
      Number.isSafeInteger(field.maximum) || field.maximum < Number.MAX_SAFE_INTEGER
        ? `允许范围：${field.minimum.toLocaleString('zh-CN')}–${field.maximum.toLocaleString('zh-CN')}。`
        : `最小值：${field.minimum.toLocaleString('zh-CN')}；上限由服务端校验。`
    )
  if (field.secret)
    parts.push('凭据不回显；不编辑保留原值，输入新值后保存会替换，编辑后清空再保存会清除凭据。')
  if (group === 'task_budget')
    parts.push('保存后对新 Run 生效；角色额度由后端派生，进行中的 Run 保持冻结额度。')
  if (
    (group === 'knowledge' && field.key.startsWith('default_')) ||
    (group === 'knowledge' && ['chunk_size', 'chunk_overlap'].includes(field.key))
  )
    parts.push('用于新知识库默认值，已有知识库请在其配置中修改。')
  return parts.filter(Boolean).join(' ')
}
