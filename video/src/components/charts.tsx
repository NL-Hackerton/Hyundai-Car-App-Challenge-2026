import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {C, fontBase} from '../theme';
import {clamp, EASE, EASE_IN_OUT} from './motion';

/** Typical DC fast-charge SOC curve: near-linear to 80 %, then tapering. t, result in 0..1. */
export const socAt = (t: number) => {
  const knee = 0.46;
  if (t <= knee) return 0.1 + (0.7 * t) / knee;
  const u = (t - knee) / (1 - knee);
  return 0.8 + 0.17 * (1 - Math.exp(-2.6 * u)) / (1 - Math.exp(-2.6));
};

/** SOC-vs-time curve with the 80 % knee marked. */
export const ChargeCurve: React.FC<{
  width: number;
  height: number;
  start: number;
  drawFrames?: number;
  labels?: boolean;
}> = ({width, height, start, drawFrames = 70, labels = true}) => {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [start, start + drawFrames], [0, 1], {...clamp, easing: EASE_IN_OUT});
  const pad = {l: 64, r: 24, t: 24, b: 52};
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;
  const x = (t: number) => pad.l + t * w;
  const y = (s: number) => pad.t + (1 - s) * h;
  const N = 120;
  const pts: string[] = [];
  for (let i = 0; i <= N; i++) {
    const t = i / N;
    pts.push(`${i === 0 ? 'M' : 'L'}${x(t).toFixed(1)},${y(socAt(t)).toFixed(1)}`);
  }
  const d = pts.join(' ');
  const area = `${d} L${x(1)},${y(0)} L${x(0)},${y(0)} Z`;
  const headT = p;
  const kneeOn = interpolate(frame, [start + drawFrames * 0.42, start + drawFrames * 0.6], [0, 1], clamp);
  const slowOn = interpolate(frame, [start + drawFrames * 0.75, start + drawFrames + 10], [0, 1], {
    ...clamp,
    easing: EASE,
  });
  return (
    <svg width={width} height={height} style={{overflow: 'visible'}}>
      <defs>
        <linearGradient id="cc-area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={C.charge} stopOpacity={0.28} />
          <stop offset="100%" stopColor={C.charge} stopOpacity={0} />
        </linearGradient>
        <clipPath id="cc-clip">
          <rect x={0} y={0} width={x(headT)} height={height} />
        </clipPath>
        <linearGradient id="cc-slow" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor={C.warn} stopOpacity={0} />
          <stop offset="30%" stopColor={C.warn} stopOpacity={0.12} />
          <stop offset="100%" stopColor={C.warn} stopOpacity={0.18} />
        </linearGradient>
      </defs>
      {/* slow zone */}
      <rect
        x={x(0.46)}
        y={pad.t}
        width={(x(1) - x(0.46)) * slowOn}
        height={h}
        fill="url(#cc-slow)"
      />
      {/* axes */}
      <line x1={pad.l} y1={y(0)} x2={x(1)} y2={y(0)} stroke={C.lineStrong} strokeWidth={2} />
      <line x1={pad.l} y1={y(0)} x2={pad.l} y2={pad.t} stroke={C.lineStrong} strokeWidth={2} />
      {/* 80 % reference */}
      <line
        x1={pad.l}
        y1={y(0.8)}
        x2={pad.l + w * kneeOn}
        y2={y(0.8)}
        stroke={C.sub}
        strokeOpacity={0.5}
        strokeWidth={2}
        strokeDasharray="6 8"
      />
      <g clipPath="url(#cc-clip)">
        <path d={area} fill="url(#cc-area)" />
        <path d={d} fill="none" stroke={C.chargeLight} strokeWidth={5} strokeLinecap="round" />
      </g>
      <circle cx={x(headT)} cy={y(socAt(headT))} r={9} fill={C.chargeLight} opacity={p > 0 ? 1 : 0} />
      <circle
        cx={x(headT)}
        cy={y(socAt(headT))}
        r={22}
        fill={C.chargeLight}
        opacity={p > 0 && p < 1 ? 0.18 : 0}
      />
      {/* knee marker */}
      <circle cx={x(0.46)} cy={y(0.8)} r={8} fill={C.ink} stroke={C.text} strokeWidth={3} opacity={kneeOn} />
      {labels && (
        <>
          <text x={pad.l - 16} y={y(0.8) + 9} textAnchor="end" fill={C.text} opacity={kneeOn}
            style={{...fontBase, fontSize: 26, fontWeight: 700}}>80%</text>
          <text x={pad.l - 16} y={y(0.1) + 9} textAnchor="end" fill={C.muted}
            style={{...fontBase, fontSize: 22, fontWeight: 500}}>10%</text>
          <text x={x(1)} y={y(0) + 38} textAnchor="end" fill={C.muted}
            style={{...fontBase, fontSize: 22, fontWeight: 500}}>충전 시간</text>
          <text x={x(0.73)} y={y(0.45)} textAnchor="middle" fill={C.warn} opacity={slowOn}
            style={{...fontBase, fontSize: 24, fontWeight: 600}}>충전 속도 저하 구간</text>
        </>
      )}
    </svg>
  );
};

/** Animated integer with Korean digit grouping. */
export const CountUp: React.FC<{
  from: number;
  to: number;
  start: number;
  frames: number;
  decimals?: number;
  style?: React.CSSProperties;
  suffix?: string;
}> = ({from, to, start, frames, decimals = 0, style, suffix = ''}) => {
  const frame = useCurrentFrame();
  const v = interpolate(frame, [start, start + frames], [from, to], {...clamp, easing: EASE});
  return (
    <span style={{fontVariantNumeric: 'tabular-nums', ...style}}>
      {v.toFixed(decimals)}
      {suffix}
    </span>
  );
};
