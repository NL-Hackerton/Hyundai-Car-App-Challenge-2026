import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {C, fontBase} from '../theme';
import {clamp, EASE, useSpring} from './motion';

/** Brand mark: a charge bar that ends in a blue "go" segment. */
export const Mark: React.FC<{size: number; delay?: number}> = ({size, delay = 0}) => {
  const frame = useCurrentFrame();
  const fill = interpolate(frame, [delay, delay + 30], [0, 1], {...clamp, easing: EASE});
  const w = size * 2.2;
  const h = size * 0.62;
  return (
    <div
      style={{
        width: w,
        height: h,
        borderRadius: h / 2,
        border: `${Math.max(2, size * 0.07)}px solid ${C.lineStrong}`,
        padding: size * 0.08,
        boxSizing: 'border-box',
        display: 'flex',
        gap: size * 0.06,
      }}
    >
      <div
        style={{
          flex: `0 0 ${62 * fill}%`,
          borderRadius: h,
          background: C.charge,
        }}
      />
      <div
        style={{
          flex: `0 0 ${28 * interpolate(frame, [delay + 22, delay + 44], [0, 1], {...clamp, easing: EASE})}%`,
          borderRadius: h,
          background: C.go,
        }}
      />
    </div>
  );
};

export const Wordmark: React.FC<{size: number; delay?: number; showMark?: boolean}> = ({
  size,
  delay = 0,
  showMark = true,
}) => {
  const p = useSpring(delay);
  return (
    <div
      style={{
        ...fontBase,
        display: 'flex',
        alignItems: 'center',
        gap: size * 0.34,
        opacity: p,
        transform: `translateY(${(1 - p) * 20}px)`,
      }}
    >
      {showMark && <Mark size={size * 0.62} delay={delay + 6} />}
      <div style={{display: 'flex', alignItems: 'baseline', gap: size * 0.26}}>
        <span style={{fontSize: size, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1}}>
          스테이고
        </span>
        <span
          style={{
            fontSize: size * 0.42,
            fontWeight: 500,
            color: C.sub,
            letterSpacing: '-0.01em',
            lineHeight: 1,
          }}
        >
          StayGo
        </span>
      </div>
    </div>
  );
};
