<script>
export default {
  onLaunch() {
    console.log('Blinder app launch')
  },
}
</script>

<style>
/* === Design tokens: editorial / pencil paper === */
page {
  --paper-bg: #ede6d6;
  --paper-card: #f5efe1;
  --paper-card-alt: #ebe4d2;
  --ink: #1a1a1a;
  --ink-soft: #4a4a4a;
  --ink-muted: #8a8378;
  --ink-faint: #b8b1a3;
  --accent: #0f3833;
  --accent-soft: #274e48;
  --accent-warm: #a34f00;
  --accent-warm-soft: #c96f10;
  --rule: #d4c9b5;
  --rule-soft: #e2d9c7;
  --danger: #8b2a0a;

  background: var(--paper-bg);
  color: var(--ink);
  font-family: "Noto Sans SC", "PingFang SC", "Hiragino Sans GB", "Source Han Sans SC", sans-serif;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

view,
text,
button,
input {
  box-sizing: border-box;
}

/* Subtle paper texture via layered gradients; scaled to not be noisy */
.paper-texture {
  background-image:
    radial-gradient(circle at 20% 10%, rgba(163, 79, 0, 0.035), transparent 40%),
    radial-gradient(circle at 85% 90%, rgba(15, 56, 51, 0.04), transparent 42%),
    repeating-linear-gradient(
      0deg,
      rgba(26, 26, 26, 0.012) 0,
      rgba(26, 26, 26, 0.012) 1rpx,
      transparent 1rpx,
      transparent 6rpx
    );
}

/* Hairline rule styles shared by pages */
.hairline {
  height: 1rpx;
  background: var(--rule);
}

.hairline--dashed {
  height: 0;
  border-top: 1rpx dashed var(--rule);
}

.page-shell--enter {
  will-change: transform, opacity;
  animation-duration: 320ms;
  animation-timing-function: cubic-bezier(0.22, 1, 0.36, 1);
  animation-fill-mode: both;
}

.page-shell--enter-from-right {
  animation-name: page-slide-in-from-right;
}

.page-shell--enter-from-left {
  animation-name: page-slide-in-from-left;
}

@keyframes page-slide-in-from-right {
  from { opacity: 0.82; transform: translate3d(54rpx, 0, 0); }
  to   { opacity: 1;    transform: translate3d(0, 0, 0); }
}

@keyframes page-slide-in-from-left {
  from { opacity: 0.82; transform: translate3d(-54rpx, 0, 0); }
  to   { opacity: 1;    transform: translate3d(0, 0, 0); }
}
</style>
