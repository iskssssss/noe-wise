// 按工作区使用各自配置，提交钩子只检查前端暂存文件。
export default Object.fromEntries(
  ['frontend-admin', 'frontend-platform', 'frontend-chat', 'frontend-shared'].flatMap((name) => [
    [
      `${name}/**/*.{js,mjs,cjs,ts,vue}`,
      [
        `eslint --config ${name}/eslint.config.mjs --fix --max-warnings 0 --no-warn-ignored`,
        'prettier --write'
      ]
    ],
    [`${name}/**/*.{json,css,scss,html,md,yaml,yml}`, 'prettier --write']
  ])
)
