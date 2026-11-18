<script setup>
defineProps({
  title: { type: String, required: true },
  note: { type: String, default: '' },
  hasData: { type: Boolean, default: false },
  emptyText: { type: String, default: '当前范围内没有数据。' },
})
</script>

<template>
  <article class="chart-card">
    <header class="chart-card__head">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="note" class="chart-card__note">{{ note }}</p>
      </div>
      <span class="chart-card__unit">UTC · 只读聚合</span>
    </header>
    <div v-if="hasData" class="chart-card__body">
      <slot />
    </div>
    <div v-else class="chart-empty" role="status">
      <strong>暂无数据</strong>
      <span>{{ emptyText }}</span>
    </div>
  </article>
</template>
