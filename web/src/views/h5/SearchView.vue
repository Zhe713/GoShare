<script setup lang="ts">
/** 搜索页：A1 语义化搜索（口语 → 结构化回显 → 智能排序 + 解释层） */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTripCard, GInput, GButton, GSkeleton, GEmpty } from '@/g-ui'
import { semanticSearch, type SemanticSearchResult } from '@/api/trip'

const router = useRouter()
const text = ref('')
const result = ref<SemanticSearchResult | null>(null)
const loading = ref(false)
const error = ref('')

async function doSearch() {
  const q = text.value.trim()
  if (!q) return
  loading.value = true
  error.value = ''
  try {
    result.value = await semanticSearch(q)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="search">
    <GNavBar title="口语搜行程" @back="router.back()" />

    <div class="search__input">
      <GInput
        v-model="text"
        placeholder="试试：「周五18点从石家庄去邯郸，能带大件」"
        size="large"
        @keyup.enter="doSearch"
      />
      <GButton type="primary" block :loading="loading" @click="doSearch">智能搜索</GButton>
    </div>

    <!-- 结构化回显 + 解释层（A1 特色） -->
    <div v-if="result" class="search__result">
      <div class="search__parsed">
        <p class="search__label">我理解的你的需求</p>
        <p class="search__parsed-text">{{ result.parsedQuery }}</p>
      </div>
      <p class="search__explain">{{ result.explanation }}</p>
      <div class="search__list">
        <GTripCard
          v-for="item in result.items"
          :key="item.tripId"
          :trip="{
            id: item.tripId,
            fromCity: item.fromCity,
            toCity: item.toCity,
            departureTime: item.departureTime,
            price: item.price,
            remainSeats: item.remainSeats,
            trustScore: item.trustScore,
            remark: item.remark,
          }"
          @click="router.push(`/trip/${item.tripId}`)"
        />
        <!-- 排序理由小字 -->
        <p v-for="item in result.items" :key="`r${item.tripId}`" class="search__reasons">
          {{ item.reasons.join('；') }}
        </p>
      </div>
    </div>

    <GSkeleton v-else-if="loading" :rows="4" :height="16" />
    <GEmpty v-else-if="error" :text="error" action-text="重试" @action="doSearch" />
    <GEmpty
      v-else
      text="用一句话描述你的行程需求，AI 帮你转成条件并排序"
      action-text="看个例子"
      @action="text = '明天去北京'; doSearch()"
    />
  </div>
</template>

<style scoped>
.search {
  position: relative;
  min-height: 100vh;
  padding-bottom: var(--sp-4);
}
.search__input {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-2);
}
.search__result {
  padding: 0 var(--sp-2);
}
.search__parsed {
  padding: var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
}
.search__label {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.search__parsed-text {
  margin-top: 2px;
  font-size: var(--fs-16);
  font-weight: var(--fw-medium);
  color: var(--brand);
}
.search__explain {
  margin: var(--sp-2) 0;
  font-size: var(--fs-13);
  color: var(--text-2);
  line-height: var(--lh-base);
}
.search__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.search__reasons {
  margin-top: -var(--sp-1);
  padding: 0 var(--sp-2);
  font-size: var(--fs-12);
  color: var(--text-3);
}
</style>
