import { stringify } from 'minimark/stringify'
import { queryCollection } from '@nuxt/content/server'

// All documentation pages in one Markdown file, for AI agents.
//
// Why this route exists: the llms-full.txt of nuxt-llms (Docus 5.13.0) converts pages
// with @nuxtjs/mdc, which overflows the stack on any **bold** text (@nuxtjs/mdc 0.22.2
// and 0.23.1 with remark-mdc 3.11.1). This route uses minimark, the same converter as
// /raw/<page>.md. Remove it, and set `llms.full` again, when upstream fixes the bug.
export default defineEventHandler(async (event) => {
  const pages = await queryCollection(event, 'docs')
    .where('extension', '=', 'md')
    .order('stem', 'ASC')
    .all()

  const parts = pages.map((page) => {
    const body = page.body.value
    if (body[0]?.[0] !== 'h1') {
      body.unshift(['blockquote', {}, page.description])
      body.unshift(['h1', {}, page.title])
    }
    return stringify({ ...page.body, type: 'minimark' }, { format: 'markdown/html' }).trim()
  })

  setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
  return `${parts.join('\n\n---\n\n')}\n`
})
