import './style.css'

const toggle = document.querySelector<HTMLButtonElement>('.menu-toggle')
const nav = document.querySelector<HTMLElement>('#navigation')
if (toggle && nav) {
  toggle.hidden = false
  document.documentElement.classList.add('enhanced')
  const closeMenu = (restoreFocus: boolean) => {
    const focusWasInMenu = nav.contains(document.activeElement)
    toggle.setAttribute('aria-expanded', 'false')
    toggle.textContent = '打开导航'
    nav.classList.remove('is-open')
    if ((restoreFocus || focusWasInMenu) && getComputedStyle(toggle).display !== 'none') {
      toggle.focus()
    }
  }
  toggle.addEventListener('click', () => {
    const open = toggle.getAttribute('aria-expanded') !== 'true'
    toggle.setAttribute('aria-expanded', String(open))
    toggle.textContent = open ? '关闭导航' : '打开导航'
    nav.classList.toggle('is-open', open)
  })
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') closeMenu(true)
  })
  document.addEventListener('click', (event) => {
    if (
      event.target instanceof Node &&
      !nav.contains(event.target) &&
      !toggle.contains(event.target)
    )
      closeMenu(false)
  })
  nav.addEventListener('click', (event) => {
    if (event.target instanceof HTMLAnchorElement) closeMenu(true)
  })
  const desktop = matchMedia('(min-width: 901px)')
  desktop.addEventListener('change', () => {
    const focusWasInNavigation = nav.contains(document.activeElement)
    const focusWasToggle = document.activeElement === toggle
    closeMenu(!desktop.matches && focusWasInNavigation)
    if (desktop.matches && focusWasToggle) nav.querySelector<HTMLAnchorElement>('a')?.focus()
  })
}

const catalogMenu = document.querySelector<HTMLDetailsElement>('.catalog-menu')
if (catalogMenu) {
  const header = document.querySelector<HTMLElement>('.site-header')!
  const summary = catalogMenu.querySelector('summary')!
  const currentLabel = catalogMenu.querySelector<HTMLElement>('[data-current-section]')!
  const links = [...catalogMenu.querySelectorAll<HTMLAnchorElement>('a[href^="#"]')]
  const sections = links.map((link) => document.querySelector<HTMLElement>(link.hash)!)
  const compact = matchMedia('(max-width: 900px)')
  const closeCatalog = (restoreFocus: boolean) => {
    if (!compact.matches) return
    catalogMenu.open = false
    if (restoreFocus) summary.focus()
  }
  const syncLayout = () => {
    const focusedInside = catalogMenu.contains(document.activeElement)
    catalogMenu.open = !compact.matches
    if (focusedInside) {
      if (compact.matches) summary.focus()
      else links.find((link) => link.hasAttribute('aria-current'))?.focus()
    }
  }
  syncLayout()
  compact.addEventListener('change', syncLayout)
  catalogMenu.addEventListener('click', (event) => {
    const link = event.target instanceof Element ? event.target.closest('a') : null
    if (!link) return
    closeCatalog(false)
    document.querySelector<HTMLElement>(link.hash)?.focus({ preventScroll: true })
  })
  catalogMenu.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && catalogMenu.open) closeCatalog(true)
  })
  document.addEventListener('click', (event) => {
    if (event.target instanceof Node && !catalogMenu.contains(event.target)) {
      closeCatalog(catalogMenu.contains(document.activeElement))
    }
  })
  let scheduled = false
  const updatePosition = () => {
    scheduled = false
    // 使用收起时的目录高度，展开目录时不改变当前阅读位置。
    const marker = header.getBoundingClientRect().height + (compact.matches ? 100 : 55)
    let active = 0
    sections.forEach((section, index) => {
      if (section.getBoundingClientRect().top <= marker) active = index
    })
    // 最后一节不足一屏时，到达页尾也应标记为当前章节。
    if (
      Math.ceil(window.scrollY + window.innerHeight) >=
      document.documentElement.scrollHeight - 2
    ) {
      active = sections.length - 1
    }
    links.forEach((link, index) => {
      if (index === active) link.setAttribute('aria-current', 'location')
      else link.removeAttribute('aria-current')
    })
    currentLabel.textContent = links[active]!.textContent
  }
  const schedulePosition = () => {
    if (!scheduled) {
      scheduled = true
      requestAnimationFrame(updatePosition)
    }
  }
  new ResizeObserver(() => {
    document.documentElement.style.setProperty('--site-header-height', `${header.offsetHeight}px`)
    schedulePosition()
  }).observe(header)
  window.addEventListener('scroll', schedulePosition, { passive: true })
  window.addEventListener('resize', schedulePosition)
  updatePosition()
}
