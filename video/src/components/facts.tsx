import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {C, fontBase} from '../theme';
import {CountUp} from './charts';
import {clamp, EASE, EASE_IN_OUT, useSpring} from './motion';

/** One-hour occupancy timer: ring sweeps 60 minutes, then turns amber. */
export const HourTimer: React.FC<{size: number; start: number}> = ({size, start}) => {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [start, start + 75], [0, 1], {...clamp, easing: EASE_IN_OUT});
  const over = interpolate(frame, [start + 75, start + 95], [0, 1], clamp);
  const r = size / 2 - 18;
  const circ = 2 * Math.PI * r;
  const col = over > 0.5 ? C.warn : C.chargeLight;
  return (
    <div style={{position: 'relative', width: size, height: size}}>
      <svg width={size} height={size} style={{transform: 'rotate(-90deg)'}}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={C.line} strokeWidth={14} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={col}
          strokeWidth={14}
          strokeLinecap="round"
          strokeDasharray={circ}
          strokeDashoffset={circ * (1 - p)}
        />
        {Array.from({length: 12}).map((_, i) => {
          const a = (i / 12) * Math.PI * 2;
          const r1 = r - 34;
          const r2 = r - (i % 3 === 0 ? 54 : 44);
          return (
            <line
              key={i}
              x1={size / 2 + Math.cos(a) * r1}
              y1={size / 2 + Math.sin(a) * r1}
              x2={size / 2 + Math.cos(a) * r2}
              y2={size / 2 + Math.sin(a) * r2}
              stroke={C.lineStrong}
              strokeWidth={3}
            />
          );
        })}
      </svg>
      <div
        style={{
          ...fontBase,
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: size * 0.02,
        }}
      >
        <div style={{fontSize: size * 0.2, fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1}}>
          <CountUp from={0} to={60} start={start} frames={75} suffix="분" />
        </div>
        <div
          style={{
            fontSize: size * 0.075,
            fontWeight: 600,
            color: C.warn,
            opacity: over,
            transform: `translateY(${(1 - over) * 8}px)`,
          }}
        >
          점유 허용 한도
        </div>
      </div>
    </div>
  );
};

/** Two-bar comparison of fines issued: 2022 vs 2025. */
export const FineBars: React.FC<{width: number; height: number; start: number; scale?: number}> = ({
  width,
  height,
  start,
  scale = 1,
}) => {
  const frame = useCurrentFrame();
  const bars = [
    {year: '2022년', v: 4},
    {year: '2025년', v: 21.5},
  ];
  const max = 21.5;
  const chartH = height - 150 * scale;
  const barW = width * 0.26;
  return (
    <div style={{...fontBase, width, height, position: 'relative'}}>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 64 * scale,
          height: 2,
          background: C.lineStrong,
        }}
      />
      {bars.map((b, i) => {
        const s = start + i * 18;
        const p = interpolate(frame, [s, s + 50], [0, 1], {...clamp, easing: EASE});
        const h = (b.v / max) * chartH * p;
        const left = width * (i === 0 ? 0.14 : 0.6);
        return (
          <React.Fragment key={b.year}>
            <div
              style={{
                position: 'absolute',
                left,
                bottom: 64 * scale,
                width: barW,
                height: h,
                borderRadius: '10px 10px 0 0',
                background: i === 0 ? 'rgba(180,192,211,0.35)' : `linear-gradient(180deg, ${C.warn}, rgba(217,164,65,0.55))`,
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: left - 40,
                width: barW + 80,
                textAlign: 'center',
                bottom: 64 * scale + h + 14 * scale,
                fontSize: (i === 0 ? 34 : 46) * scale,
                fontWeight: 800,
                letterSpacing: '-0.02em',
                opacity: p,
                color: i === 0 ? C.sub : C.text,
                whiteSpace: 'nowrap',
              }}
            >
              {i === 0 ? (
                <>약 <CountUp from={0} to={4} start={s} frames={50} />만 건</>
              ) : (
                <>약 <CountUp from={0} to={21.5} start={s} frames={50} decimals={1} />만 건</>
              )}
            </div>
            <div
              style={{
                position: 'absolute',
                left,
                width: barW,
                textAlign: 'center',
                bottom: 14 * scale,
                fontSize: 26 * scale,
                fontWeight: 600,
                color: C.muted,
              }}
            >
              {b.year}
            </div>
          </React.Fragment>
        );
      })}
    </div>
  );
};

/** Three scattered information sources collapsing into one mental calculation. */
export const MergingChips: React.FC<{width: number; height: number; start: number; scale?: number}> = ({
  width,
  height,
  start,
  scale = 1,
}) => {
  const frame = useCurrentFrame();
  const chips = [
    {t: '차량 화면', d: '배터리 잔량', x: 0.0, y: 0.04, c: C.chargeLight},
    {t: '내비게이션', d: '도착 예상', x: 0.56, y: 0.14, c: C.goLight},
    {t: '충전 앱', d: '충전 속도', x: 0.16, y: 0.66, c: C.warn},
  ];
  const merge = interpolate(frame, [start + 60, start + 100], [0, 1], {...clamp, easing: EASE_IN_OUT});
  const chipW = 320 * scale;
  const chipH = 110 * scale;
  const gap = 14 * scale;
  const pad = 26 * scale;
  const colH = chipH * 3 + gap * 2;
  const colLeft = width / 2 - chipW / 2;
  const colTop = (height - colH - 70 * scale) / 2;
  const box = useSpring(start + 88);
  return (
    <div style={{...fontBase, width, height, position: 'relative'}}>
      {/* destination: the driver's head-math */}
      <div
        style={{
          position: 'absolute',
          left: colLeft - pad,
          top: colTop - pad,
          width: chipW + pad * 2,
          height: colH + pad * 2,
          borderRadius: 28 * scale,
          border: `3px dashed ${C.lineStrong}`,
          opacity: box,
          transform: `scale(${0.94 + box * 0.06})`,
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: colTop + colH + pad + 22 * scale,
          textAlign: 'center',
          fontSize: 34 * scale,
          fontWeight: 700,
          opacity: box,
        }}
      >
        운전자가 직접 계산
      </div>
      {chips.map((ch, i) => {
        const appear = interpolate(frame, [start + i * 10, start + i * 10 + 18], [0, 1], {
          ...clamp,
          easing: EASE,
        });
        const x0 = ch.x * width;
        const y0 = ch.y * height;
        const x1 = colLeft;
        const y1 = colTop + i * (chipH + gap);
        const x = x0 + (x1 - x0) * merge;
        const y = y0 + (y1 - y0) * merge;
        return (
          <div
            key={ch.t}
            style={{
              position: 'absolute',
              left: x,
              top: y,
              width: chipW,
              height: chipH,
              boxSizing: 'border-box',
              padding: `${18 * scale}px ${24 * scale}px`,
              borderRadius: 18 * scale,
              background: C.panel,
              border: `1px solid ${C.lineStrong}`,
              boxShadow: '0 18px 40px rgba(0,0,0,0.35)',
              opacity: appear,
              transform: `translateY(${(1 - appear) * 24}px) rotate(${(1 - merge) * (i - 1) * 3}deg)`,
              zIndex: 3 - i,
            }}
          >
            <div style={{display: 'flex', alignItems: 'center', gap: 12 * scale}}>
              <span style={{width: 12 * scale, height: 12 * scale, borderRadius: 99, background: ch.c}} />
              <span style={{fontSize: 30 * scale, fontWeight: 700}}>{ch.t}</span>
            </div>
            <div style={{fontSize: 26 * scale, color: C.sub, marginTop: 8 * scale, fontWeight: 500}}>
              {ch.d}
            </div>
          </div>
        );
      })}
    </div>
  );
};
