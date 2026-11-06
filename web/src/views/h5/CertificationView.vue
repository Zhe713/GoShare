<script setup lang="ts">
/** 车主认证：提交认证申请（车牌/车型/座位数/驾驶证号）+ 查看我的认证状态 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GInput, GToast } from '@/g-ui'
import { certificationApply, myCertification, type Certification } from '@/api/user'
import { getToken } from '@/api/request'

const router = useRouter()
const licensePlate = ref('')
const vehicleModel = ref('')
const seatCount = ref(4)
const drivingLicenseNo = ref('')
const submitting = ref(false)
const toast = ref(false)
const toastMsg = ref('')
const record = ref<Certification | null>(null)
const loaded = ref(false)

onMounted(async () => {
  if (!getToken()) return
  try {
    record.value = await myCertification()
  } catch {
    record.value = null
  } finally {
    loaded.value = true
  }
})

function statusText(s: number) {
  return s === 0 ? '待审核' : s === 1 ? '已通过' : s === 2 ? '已拒绝' : String(s)
}

async function submit() {
  if (!licensePlate.value.trim()) {
    toastMsg.value = '请填写车牌号'
    toast.value = true
    return
  }
  submitting.value = true
  try {
    await certificationApply({
      licensePlate: licensePlate.value.trim(),
      vehicleModel: vehicleModel.value.trim(),
      seatCount: seatCount.value,
      drivingLicenseNo: drivingLicenseNo.value.trim() || undefined,
    })
    toastMsg.value = '认证申请已提交，等待平台审核'
    toast.value = true
    setTimeout(() => router.push('/mine'), 900)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="cert">
    <GNavBar title="车主认证" @back="router.back()" />

    <!-- 已有认证记录 -->
    <GCard v-if="record" class="cert__card">
      <h3 class="cert__section">我的认证</h3>
      <div class="cert__status" :class="`cert__status--${record.status}`">{{ statusText(record.status) }}</div>
      <div class="cert__info">
        <p>车牌：{{ record.licensePlate }}</p>
        <p>车型：{{ record.vehicleModel || '未填写' }}</p>
        <p>可拼座位：{{ record.seatCount }}</p>
        <p v-if="record.auditRemark">审核备注：{{ record.auditRemark }}</p>
      </div>
      <p v-if="record.status === 0" class="cert__hint">审核通过后即可在「我的」页发布行程。</p>
      <p v-else-if="record.status === 1" class="cert__hint">已认证，去「我的」页发布行程吧。</p>
    </GCard>

    <!-- 提交申请 -->
    <GCard class="cert__card">
      <h3 class="cert__section">提交认证申请</h3>
      <GInput v-model="licensePlate" label="车牌号" placeholder="如：京A·88888" />
      <GInput v-model="vehicleModel" label="车型" placeholder="如：特斯拉Model3" />
      <div class="cert__seats">
        <span>可拼座位数</span>
        <div class="cert__seat-btns">
          <button v-for="n in 6" :key="n" class="cert__seat" :class="{ 'cert__seat--on': seatCount === n }" @click="seatCount = n">{{ n }}</button>
        </div>
      </div>
      <GInput v-model="drivingLicenseNo" label="驾驶证号（可选）" placeholder="驾驶证档案编号" />
      <p class="cert__hint">认证由平台人工审核（演示环境：提交后状态为待审核）。</p>
      <GButton type="primary" block :loading="submitting" @click="submit">提交认证申请</GButton>
    </GCard>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.cert {
  min-height: 100vh;
  padding-bottom: 24px;
}

.cert__card {
  margin: var(--sp-2);
}

.cert__section {
  font-size: var(--fs-14);
  font-weight: var(--fw-semibold);
  margin-bottom: var(--sp-2);
}

.cert__status {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--r-sm);
  font-size: var(--fs-12);
  margin-bottom: var(--sp-2);
  background: var(--surface);
  color: var(--text-2);
}

.cert__status--1 {
  color: var(--c-green-500);
}

.cert__status--2 {
  color: var(--c-red-500);
}

.cert__info {
  font-size: var(--fs-13);
  color: var(--text-2);
  line-height: 1.8;
}

.cert__hint {
  margin: var(--sp-1) 0 var(--sp-2);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.cert__seats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-14);
  color: var(--text-1);
}

.cert__seat-btns {
  display: flex;
  gap: var(--sp-1);
}

.cert__seat {
  min-width: 36px;
  height: 36px;
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-1);
  font-size: var(--fs-14);
}

.cert__seat--on {
  background: var(--brand-grad);
  color: var(--on-brand);
  border-color: transparent;
}
</style>
