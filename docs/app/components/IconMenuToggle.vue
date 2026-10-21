<!-- claude 2026-10-21: created: name the mobile navigation control and expose its state. -->
<script setup lang="ts">
defineProps<{ open: boolean }>()
</script>

<template>
  <UButton
    :aria-label="open ? 'Close navigation' : 'Open navigation'"
    :aria-expanded="open"
    size="sm"
    variant="ghost"
    color="neutral"
    class="-me-1.5"
    square
  >
    <svg
      xmlns="http://www.w3.org/2000/svg"
      class="size-5"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      aria-hidden="true"
    >
      <path v-if="open" d="M6 6l12 12M6 18 18 6" />
      <path v-else d="M4 6h16M4 12h16M4 18h16" />
    </svg>
  </UButton>
</template>
