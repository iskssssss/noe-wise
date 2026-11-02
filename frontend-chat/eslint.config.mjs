import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import prettier from 'eslint-config-prettier/flat'
import vue from 'eslint-plugin-vue'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// 会话端独立检查 Vue 与 TypeScript，不读取后台公共层的规则或配置。
export default defineConfig(
  globalIgnores(['dist/**', 'coverage/**', 'node_modules/**', '**/*.log', '**/*.d.ts']),
  {
    files: ['**/*.{js,mjs,cjs,ts,vue}'],
    extends: [js.configs.recommended, tseslint.configs.recommended, vue.configs['flat/essential']],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        vueFeatures: { filter: false }
      }
    },
    rules: {
      eqeqeq: ['error', 'always'],
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'no-empty': ['error', { allowEmptyCatch: true }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }
      ]
    }
  },
  {
    files: ['*.config.{js,mjs,cjs,ts}', 'scripts/**/*.{js,mjs,cjs,ts}', '**/*.test.ts'],
    languageOptions: { globals: globals.node }
  },
  prettier
)
