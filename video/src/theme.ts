import {loadFont} from '@remotion/fonts';
import type {CSSProperties} from 'react';
import {staticFile} from 'remotion';

export const FONT = 'Pretendard';

const WEIGHTS: Array<[string, string]> = [
  ['Regular', '400'],
  ['Medium', '500'],
  ['SemiBold', '600'],
  ['Bold', '700'],
  ['ExtraBold', '800'],
];

for (const [name, weight] of WEIGHTS) {
  loadFont({
    family: FONT,
    url: staticFile(`fonts/Pretendard-${name}.woff2`),
    weight,
    format: 'woff2',
  });
}

export const C = {
  ink: '#0b1220',
  inkDeep: '#070c16',
  panel: '#121c30',
  line: 'rgba(214, 226, 245, 0.12)',
  lineStrong: 'rgba(214, 226, 245, 0.24)',
  text: '#eef2f8',
  sub: '#b4c0d3',
  muted: '#8392a9',
  go: '#1f6fd1',
  goLight: '#5b9bea',
  charge: '#2f9e62',
  chargeLight: '#4cc283',
  warn: '#d9a441',
};

export const FPS = 30;

export const fontBase: CSSProperties = {
  fontFamily: `${FONT}, sans-serif`,
  color: C.text,
  wordBreak: 'keep-all',
  fontFeatureSettings: '"tnum" 0',
};

export const DISCLOSURE =
  'Pleos Connect 에뮬레이터 화면 녹화 · 차량 값은 가상 콘솔 입력 · 배경음악 AI 생성';
