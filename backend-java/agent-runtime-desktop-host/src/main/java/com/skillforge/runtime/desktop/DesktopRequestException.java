package com.skillforge.runtime.desktop;

/** 可预期的桌面请求拒绝；调用方按明确错误码映射，不解析技术异常文案。 */
final class DesktopRequestException extends RuntimeException {
    enum Code {
        MODEL_NOT_CONFIGURED("请先配置云端模型。"),
        DESKTOP_SHUTTING_DOWN("客户端正在关闭，无法受理新任务。"),
        DESKTOP_QUEUE_FULL("本地任务队列已满，请等待现有任务结束。"),
        WORKSPACE_UNAVAILABLE("无法访问会话的工作目录，请检查目录是否存在及访问权限。"),
        DESKTOP_RUN_FORMAT_UNSUPPORTED("此会话包含旧版执行协议，历史记录仍保留，但无法继续或恢复执行。请新建会话开始任务。");

        private final String message;
        Code(String message) { this.message = message; }
        String publicMessage() { return message; }
    }

    private final Code code;
    DesktopRequestException(Code code) { super(code.name()); this.code = code; }
    DesktopRequestException(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    Code code() { return code; }
}
