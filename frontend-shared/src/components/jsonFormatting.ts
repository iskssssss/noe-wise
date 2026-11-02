import { jsonLanguage } from '@codemirror/lang-json'

/** 只重排有效 JSON 的结构与空白，不解码数值、字符串或重复键。 */
export function formatJsonText(source: string): string | null {
  if (!source.trim()) return null
  const cursor = jsonLanguage.parser.parse(source).cursor()
  do {
    if (cursor.type.isError) return null
  } while (cursor.next())

  const tokens = source.match(/"(?:\\[\s\S]|[^"\\])*"|[{}[\],:]|[^\s{}[\],:]+/g) ?? []
  const output: string[] = []
  let depth = 0
  const newLine = () => output.push('\n', '  '.repeat(depth))
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]!
    if (token === '{' || token === '[') {
      output.push(token)
      depth++
      const closing = token === '{' ? '}' : ']'
      if (tokens[index + 1] !== closing) newLine()
    } else if (token === '}' || token === ']') {
      depth--
      const opening = token === '}' ? '{' : '['
      if (tokens[index - 1] !== opening) newLine()
      output.push(token)
    } else if (token === ',') {
      output.push(token)
      newLine()
    } else {
      output.push(token === ':' ? ': ' : token)
    }
  }
  return output.join('')
}
