import { defineConfig } from '@playwright/test'
import workflow from './playwright.workflow.config'

const outputDir = process.env.WORKFLOW_LIFECYCLE_ARTIFACT_DIR ?? 'test-results/workflow-lifecycle'

export default defineConfig({
  ...workflow,
  testMatch: 'workflow-lifecycle.spec.ts',
  outputDir,
  reporter: [['list'], ['junit', { outputFile: `${outputDir}/junit.xml` }]],
  projects: workflow.projects?.filter((project) =>
    ['desktop-light', 'narrow-dark'].includes(project.name ?? '')
  )
})
