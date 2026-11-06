import React from 'react';
import {Html5Audio, interpolate, staticFile, useVideoConfig} from 'remotion';

/**
 * Background music, aligned 1:1 with the composition from frame 0.
 * The supplied tracks are pre-mastered (length, loop seam and fade-out baked in),
 * so only a 0.3 s in-fade is applied. Renders nothing when src is null.
 */
export const Music: React.FC<{src: string | null; volume: number}> = ({src, volume}) => {
  const {fps} = useVideoConfig();
  if (!src) return null;
  return (
    <Html5Audio
      src={staticFile(src)}
      volume={(f) => volume * interpolate(f, [0, fps * 0.3], [0, 1], {extrapolateRight: 'clamp'})}
    />
  );
};
