import React from 'react';
import {
  AbsoluteFill,
  Easing,
  interpolate,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
import {C} from '../theme';

export const EASE = Easing.bezier(0.22, 1, 0.36, 1);
export const EASE_IN_OUT = Easing.bezier(0.65, 0, 0.35, 1);

export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

/** 0→1 eased progress between two frames. */
export const useProgress = (from: number, to: number, easing = EASE) => {
  const frame = useCurrentFrame();
  return interpolate(frame, [from, to], [0, 1], {...clamp, easing});
};

export const useSpring = (delay = 0, damping = 200, durationInFrames?: number) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return spring({frame: frame - delay, fps, config: {damping}, durationInFrames});
};

/** Fades a whole scene in and out at its own edges (dip through the backdrop). */
export const SceneFade: React.FC<{
  duration: number;
  fadeIn?: number;
  fadeOut?: number;
  children: React.ReactNode;
}> = ({duration, fadeIn = 14, fadeOut = 14, children}) => {
  const frame = useCurrentFrame();
  const o = Math.min(
    fadeIn > 0 ? interpolate(frame, [0, fadeIn], [0, 1], {...clamp, easing: EASE}) : 1,
    fadeOut > 0
      ? interpolate(frame, [duration - fadeOut, duration], [1, 0], {...clamp, easing: EASE_IN_OUT})
      : 1,
  );
  const lift = interpolate(frame, [0, fadeIn + 6], [10, 0], {...clamp, easing: EASE});
  return (
    <AbsoluteFill style={{opacity: o, transform: `translateY(${lift}px)`}}>{children}</AbsoluteFill>
  );
};

/** Spring-in from a short vertical offset. */
export const Rise: React.FC<{
  delay?: number;
  y?: number;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({delay = 0, y = 28, style, children}) => {
  const p = useSpring(delay);
  return (
    <div style={{opacity: p, transform: `translateY(${(1 - p) * y}px)`, ...style}}>{children}</div>
  );
};

/** Text revealed with a clipped upward wipe — for display lines. */
export const WipeLine: React.FC<{
  delay?: number;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({delay = 0, style, children}) => {
  const p = useSpring(delay, 200, 26);
  return (
    <div style={{overflow: 'hidden', paddingBottom: '0.12em', ...style}}>
      <div style={{transform: `translateY(${(1 - p) * 105}%)`, opacity: Math.min(1, p * 1.6)}}>
        {children}
      </div>
    </div>
  );
};

export const Backdrop: React.FC<{vertical?: boolean}> = ({vertical}) => {
  const frame = useCurrentFrame();
  const drift = Math.sin(frame / 240) * 4;
  return (
    <AbsoluteFill style={{backgroundColor: C.ink}}>
      <AbsoluteFill
        style={{
          background: vertical
            ? `radial-gradient(70% 40% at ${30 + drift}% 18%, rgba(31,111,209,0.20), transparent 70%),
               radial-gradient(60% 35% at ${75 - drift}% 88%, rgba(47,158,98,0.10), transparent 70%)`
            : `radial-gradient(55% 70% at ${18 + drift}% 12%, rgba(31,111,209,0.18), transparent 70%),
               radial-gradient(45% 60% at ${88 - drift}% 92%, rgba(47,158,98,0.09), transparent 70%)`,
        }}
      />
      <AbsoluteFill
        style={{
          background:
            'radial-gradient(120% 90% at 50% 50%, transparent 55%, rgba(3,6,12,0.55) 100%)',
        }}
      />
    </AbsoluteFill>
  );
};
