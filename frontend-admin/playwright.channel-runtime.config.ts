import base from './playwright.channel-onboarding.config'
import { defineConfig } from '@playwright/test'
export default defineConfig(base, { testMatch: 'channel-runtime-real.spec.ts', timeout: 180_000 })
