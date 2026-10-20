<!--
  Embeds a web demo (docs/public/demo) in an iframe of a fixed height in em.
  Use it in a page as `::demo{src="/demo/assistant/index.html"}`. The page of
  the demo keeps its own Content-Security-Policy inside the iframe.
  claude 2026-10-20: created (L5)
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
    />
  </div>
</template>
