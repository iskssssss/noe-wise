/** 测试使用单个会话观察连接，提交只发布到现有订阅，不假定每次 POST 都重新 GET。 */
export class ChatEventFeed {
  private sequence = 0
  private queued: Uint8Array[] = []
  private controllers = new Set<ReadableStreamDefaultController<Uint8Array>>()
  publish(event: string, data: unknown) {
    const frame = new TextEncoder().encode(
      `id: ${++this.sequence}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`
    )
    if (!this.controllers.size) this.queued.push(frame)
    else for (const controller of this.controllers) controller.enqueue(frame)
  }
  response() {
    let active: ReadableStreamDefaultController<Uint8Array>
    return new Response(
      new ReadableStream<Uint8Array>({
        start: (controller) => {
          active = controller
          this.controllers.add(controller)
          for (const frame of this.queued.splice(0)) controller.enqueue(frame)
        },
        cancel: () => {
          this.controllers.delete(active)
        }
      }),
      { headers: { 'Content-Type': 'text/event-stream' } }
    )
  }
}
