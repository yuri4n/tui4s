<!--
  Embeds a web demo (docs/public/demo) in an iframe of a fixed height in em.
  Use it in a page as `::demo{src="/demo/assistant/index.html?demo"}`. The page
  of the demo keeps its own Content-Security-Policy inside the iframe. The
  iframe is sandboxed with scripts and forms only (no allow-same-origin): the
  demo gets an origin of its own (opaque), so it cannot read the storage or the
  cookies of the docs site, and it has no `localStorage` (the assistant keeps
  its storage in memory with `?demo`). Without allow-forms, Chrome blocks the
  submit event, and the forms of the demo (the settings) do nothing.
  claude 2026-10-20: created (L5); changed: the sandbox (L5 review)
-->
<script setup lang="ts">
withDefaults(
  defineProps<{
    /** The path of the page of the demo. */
    src: string
    /** The height of the iframe (CSS), in em. */
    height?: string
    /** The title of the iframe, for a screen reader. */
    title?: string
  }>(),
  { height: '36em', title: 'The tui4s assistant on the web' },
)
</script>

<template>
  <div class="tui4s-demo not-prose my-6 overflow-hidden rounded-md border border-default">
    <iframe
      :src="src"
      :title="title"
      :style="{ height, width: '100%', display: 'block' }"
      loading="lazy"
      referrerpolicy="no-referrer"
      sandbox="allow-scripts allow-forms"
    />
  </div>
</template>
