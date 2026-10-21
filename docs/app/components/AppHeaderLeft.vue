<!-- claude 2026-10-21: created: retain the generated logo beside a docs path label. -->
<template>
  <div class="flex items-center gap-4">
    <NuxtLink to="/" aria-label="tui4s home">
      <AppHeaderLogo class="h-12 w-auto shrink-0" />
    </NuxtLink>
    <span class="hidden sm:inline font-mono text-dimmed" aria-hidden="true">/</span>
    <NuxtLink
      to="/getting-started/installation"
      class="hidden sm:inline font-mono text-xs text-muted hover:text-highlighted"
    >
      docs
    </NuxtLink>
  </div>
</template>
