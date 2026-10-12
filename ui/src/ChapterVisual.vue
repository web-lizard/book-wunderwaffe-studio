<template>
  <div class="chapter-visual-content" :class="{ comic }" :style="{ opacity }" aria-hidden="true">
    <div class="chapter-visual-backdrop" :style="backdropStyle"></div>
    <img class="chapter-visual-image" :src="src" :style="percentRect(clip.layout.image)" alt="" />
    <div v-if="!comic" class="chapter-visual-badge" :style="{ ...percentRect(clip.layout.badge), fontSize: `${Math.max(9, sceneWidth * 0.0125)}px` }">{{ clip.chapterTitle }}</div>
  </div>
</template>

<script setup>
import { percentRect } from './scenePlan.js'
import { computed } from 'vue'
const props = defineProps({ clip: { type: Object, required: true }, src: { type: String, required: true },
  opacity: { type: Number, default: 0 }, sceneWidth: { type: Number, default: 960 }, comic: { type: Boolean, default: false } })
const backdropStyle = computed(() => ({
  backgroundImage: `url("${props.src.replaceAll('"', '%22')}")`,
  filter: `blur(${props.clip.layout.blur * props.sceneWidth / 1920}px) brightness(${props.clip.layout.brightness})`,
}))
</script>
