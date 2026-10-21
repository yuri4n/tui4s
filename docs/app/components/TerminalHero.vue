<!--
  A terminal frame for the recorded Counter example on the home page.
  claude 2026-10-21: created: the home hero uses the existing Counter cast.
-->
<template>
  <section class="tui-hero not-prose" aria-labelledby="tui-hero-title">
    <UContainer>
      <div class="tui-hero__layout">
        <div class="tui-hero__intro">
          <!-- claude 2026-10-21: created: the platform label uses a shell prompt. -->
          <p class="tui-hero__eyebrow"><span aria-hidden="true">&gt;_</span> Scala 3 / kyo-ui</p>

          <!-- claude 2026-10-21: created: one heading gives the page its title. -->
          <h1 id="tui-hero-title" class="tui-hero__title">
            kyo-ui<br>
            <span>in the terminal.</span>
          </h1>

          <!-- claude 2026-10-21: created: the introduction comes from the page so raw Markdown keeps its links. -->
          <div class="tui-hero__description">
            <slot name="description" />
          </div>

          <!-- claude 2026-10-21: created: the main links lead to installation and source. -->
          <div class="tui-hero__actions">
            <UButton
              to="/getting-started/installation"
              color="neutral"
              size="xl"
              trailing-icon="i-lucide-arrow-right"
              class="tui-hero__button"
            >
              Get started
            </UButton>
            <UButton
              to="https://github.com/yuri4n/tui4s"
              color="neutral"
              variant="outline"
              size="xl"
              icon="i-simple-icons-github"
              class="tui-hero__button"
            >
              Source on GitHub
            </UButton>
          </div>
        </div>

        <!-- claude 2026-10-21: created: the terminal frame labels the real recording. -->
        <figure class="tui-hero__terminal" aria-labelledby="tui-hero-cast-title">
          <div class="tui-hero__terminal-bar">
            <span class="tui-hero__window-marks" aria-hidden="true"><i /><i /><i /></span>
            <span id="tui-hero-cast-title">counter — tui4s</span>
            <span class="tui-hero__terminal-size" aria-hidden="true">60 × 6</span>
          </div>

          <!-- claude 2026-10-21: created: the command is the example's start script. -->
          <div class="tui-hero__command">
            <span aria-hidden="true">$</span>
            <code>examples/target/bin/counter</code>
          </div>

          <!-- claude 2026-10-21: created: the player retains its own controls and lifecycle. -->
          <div class="tui-hero__recording">
            <Cast src="/casts/counter.cast" />
          </div>

          <!-- claude 2026-10-21: created: the caption distinguishes a recording from a live app. -->
          <figcaption class="tui-hero__terminal-footer">
            <span><span class="tui-hero__status" aria-hidden="true" />Recorded example</span>
            <NuxtLink to="/getting-started/first-app">Build your first app <span aria-hidden="true">↗</span></NuxtLink>
          </figcaption>
        </figure>
      </div>

      <!-- claude 2026-10-21: created: the compact strip shows the rendering path. -->
      <div class="tui-hero__pipeline" aria-label="Rendering path: kyo-ui through tui4s to the terminal">
        <span>kyo-ui</span>
        <span class="tui-hero__connector" aria-hidden="true">→</span>
        <span class="tui-hero__pipeline-runner">tui4s</span>
        <span class="tui-hero__connector" aria-hidden="true">→</span>
        <span>terminal</span>
        <span class="tui-hero__pipeline-note">Your UI. In cells.</span>
      </div>
    </UContainer>
  </section>
</template>

<style scoped>
/* claude 2026-10-21: created: the hero uses the site's background and border tokens. */
.tui-hero {
  max-width: var(--ui-container);
  margin-inline: auto;
  padding-top: clamp(3.5rem, 7vw, 6.5rem);
  border-inline: 1px solid var(--ui-border);
  border-bottom: 1px solid var(--ui-border);
  background-color: var(--ui-bg);
  background-image: linear-gradient(var(--tui-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--tui-grid) 1px, transparent 1px);
  background-size: 64px 64px;
}

/* claude 2026-10-21: created: the desktop layout balances the heading and recording. */
.tui-hero__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: clamp(2rem, 5vw, 5rem);
  padding-bottom: clamp(3.5rem, 6vw, 5.5rem);
}

/* claude 2026-10-21: created: the shell label uses the system monospace stack. */
.tui-hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 0 0 1.5rem;
  color: var(--ui-text-muted);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.75rem;
  letter-spacing: 0.04em;
}

/* claude 2026-10-21: created: the prompt and rendering stage share one accent. */
.tui-hero__eyebrow > span,
.tui-hero__command > span,
.tui-hero__pipeline-runner {
  color: var(--tui-accent, var(--ui-primary));
}

/* claude 2026-10-21: created: the large heading stays compact in both colour schemes. */
.tui-hero__title {
  margin: 0;
  color: var(--ui-text-highlighted);
  font-size: clamp(2.75rem, 4.8vw, 4.5rem);
  font-weight: 650;
  line-height: 1.08;
  letter-spacing: -0.065em;
}

/* claude 2026-10-21: created: the second line has lower visual emphasis. */
.tui-hero__title > span {
  color: var(--ui-text-muted);
}

/* claude 2026-10-21: created: the introduction has a readable line length. */
.tui-hero__description {
  max-width: 27rem;
  margin: 1.75rem 0 0;
  color: var(--ui-text-muted);
  font-size: 1rem;
  line-height: 1.8;
  text-wrap: pretty;
}

/* claude 2026-10-21: created: links remain visible inside the Markdown description. */
.tui-hero__description :deep(a) {
  color: var(--ui-text-highlighted);
  text-decoration: underline;
  text-decoration-color: var(--ui-border-accented);
  text-underline-offset: 0.2em;
}

/* claude 2026-10-21: created: the primary actions wrap without narrowing their labels. */
.tui-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 2rem;
}

/* claude 2026-10-21: created: the buttons use a restrained terminal frame. */
.tui-hero__button {
  border-radius: 0.25rem;
  font-size: 0.875rem;
}

/* claude 2026-10-21: created: the terminal frame remains inside its grid column. */
.tui-hero__terminal {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--ui-border);
  border-radius: 0.5rem;
  background: var(--ui-bg);
  box-shadow: 0 16px 64px -24px rgb(0 0 0 / 0.3);
  font-family: var(--font-mono, ui-monospace, monospace);
}

/* claude 2026-10-21: created: the title bar separates window controls from the recording. */
.tui-hero__terminal-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  min-height: 2.75rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--ui-border);
  color: var(--ui-text-muted);
  font-size: 0.6875rem;
}

/* claude 2026-10-21: created: neutral window marks are decorative. */
.tui-hero__window-marks {
  display: flex;
  gap: 0.375rem;
}

/* claude 2026-10-21: created: the window marks retain their outline in both themes. */
.tui-hero__window-marks > i {
  width: 0.5rem;
  height: 0.5rem;
  border: 1px solid var(--ui-border);
  border-radius: 50%;
  background: color-mix(in srgb, var(--ui-text-muted) 20%, transparent);
}

/* claude 2026-10-21: created: the command wraps on narrow screens. */
.tui-hero__command {
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  padding: 1.5rem 1.25rem;
  color: var(--ui-text-highlighted);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

/* claude 2026-10-21: created: the recording keeps its native dark terminal surface. */
.tui-hero__recording {
  padding: 0.875rem;
  background: #121314;
}

/* claude 2026-10-21: created: this frame replaces only the nested cast's outer spacing. */
.tui-hero__recording :deep(.tui4s-cast) {
  margin: 0;
  border: 0;
  border-radius: 0;
}

/* claude 2026-10-21: created: the recording caption stays separate from player controls. */
.tui-hero__terminal-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.875rem 1rem;
  border-top: 1px solid var(--ui-border);
  color: var(--ui-text-muted);
  font-size: 0.625rem;
  line-height: 1.5;
}

/* claude 2026-10-21: created: the status marker aligns with its caption. */
.tui-hero__terminal-footer > span {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

/* claude 2026-10-21: created: the static dot marks the recorded example. */
.tui-hero__status {
  width: 0.375rem;
  height: 0.375rem;
  border-radius: 50%;
  background: var(--tui-accent, var(--ui-primary));
}

/* claude 2026-10-21: created: the caption link exposes pointer and keyboard states. */
.tui-hero__terminal-footer a:hover,
.tui-hero__terminal-footer a:focus-visible {
  color: var(--ui-text-highlighted);
  text-decoration: underline;
  text-underline-offset: 0.25rem;
}

/* claude 2026-10-21: created: a quiet metadata row closes the hero. */
.tui-hero__pipeline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
  padding: 1.25rem 0;
  border-top: 1px solid var(--ui-border);
  color: var(--ui-text-muted);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.6875rem;
}

/* claude 2026-10-21: created: the explanatory note sits after the rendering path. */
.tui-hero__pipeline-note {
  margin-left: auto;
}

/* claude 2026-10-21: created: smaller screens stack the content in reading order. */
@media (max-width: 960px) {
  .tui-hero__layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 2.5rem;
  }

  .tui-hero__description {
    max-width: 33rem;
  }

  .tui-hero__terminal {
    width: 100%;
    max-width: 36rem;
  }
}

/* claude 2026-10-21: created: narrow screens keep the command and metadata legible. */
@media (max-width: 480px) {
  .tui-hero__terminal-size {
    display: none;
  }

  .tui-hero__terminal-bar {
    justify-content: flex-start;
    gap: 1rem;
  }

  .tui-hero__pipeline {
    gap: 0.75rem;
  }

  .tui-hero__pipeline-note {
    flex-basis: 100%;
    margin-left: 0;
  }
}
</style>
