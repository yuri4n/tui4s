<!--
  Plays a terminal cast of an example (asciinema v2, docs/public/casts) with
  asciinema-player. Use it in a page as `::cast{src="/casts/counter.cast"}`.
  The size of the terminal comes from the header of the cast. The player runs
  only in the browser; the server renders an empty box.
  claude 2026-10-20: created (L5)
-->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { Player } from 'asciinema-player'
import 'asciinema-player/dist/bundle/asciinema-player.css'

const props = defineProps<{
  /** The path of the cast, for example `/casts/counter.cast`. */
  src: string
  /** The largest width of the player (CSS), for a cast with few columns. */
  maxWidth?: string
}>()

const box = ref<HTMLElement | null>(null)
const ready = ref(false)
let player: Player | undefined

onMounted(async () => {
  const { create } = await import('asciinema-player')
  if (box.value) {
    player = create(props.src, box.value, {
      fit: 'width',
      preload: true,
      poster: 'npt:0:2',
      idleTimeLimit: 1,
      terminalFontSize: 'small',
      theme: 'asciinema',
    })
    ready.value = true
  }
})

onBeforeUnmount(() => player?.dispose())
</script>

<template>
  <div class="tui4s-cast not-prose my-6 overflow-hidden rounded-md" :style="{ maxWidth }">
    <!-- the box keeps some height until the player is there -->
    <div ref="box" :class="ready ? '' : 'min-h-32'" />
  </div>
</template>
