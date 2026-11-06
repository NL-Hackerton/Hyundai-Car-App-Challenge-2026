import React from 'react';
import {
  AbsoluteFill,
  Img,
  interpolate,
  OffthreadVideo,
  Series,
  staticFile,
  useCurrentFrame,
} from 'remotion';
import {C, FPS, fontBase} from '../theme';
import {clipBadge, clipFrames, isImage, type Clip, type TimelineSection} from '../timeline';
import {clamp, EASE} from './motion';

const BEZEL = 16;

/** Car centre-display bezel around a screen of the given width and aspect ratio (default 16:9). */
export const DeviceFrame: React.FC<{
  screenWidth: number;
  aspect?: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({screenWidth, aspect = 16 / 9, children, style}) => {
  const screenHeight = Math.round(screenWidth / aspect);
  return (
    <div
      style={{
        width: screenWidth + BEZEL * 2,
        height: screenHeight + BEZEL * 2,
        padding: BEZEL,
        borderRadius: 30,
        background: 'linear-gradient(180deg, #1d2536 0%, #0c111c 55%, #070a12 100%)',
        boxShadow:
          '0 50px 120px rgba(0,0,0,0.55), 0 12px 30px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.14), inset 0 -1px 0 rgba(0,0,0,0.6)',
        boxSizing: 'border-box',
        position: 'relative',
        ...style,
      }}
    >
      <div
        style={{
          width: screenWidth,
          height: screenHeight,
          borderRadius: 14,
          overflow: 'hidden',
          position: 'relative',
          background: '#f1f3f6',
          boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.5)',
        }}
      >
        {children}
        {/* faint glass reflection */}
        <AbsoluteFill
          style={{
            background:
              'linear-gradient(115deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0) 38%)',
            pointerEvents: 'none',
          }}
        />
      </div>
    </div>
  );
};

/**
 * Style for the media element. Uncropped clips fill the screen with object-fit;
 * cropped clips are scaled so the crop rectangle covers the screen and translated into place.
 */
const mediaStyle = (clip: Clip, w: number, h: number, zoom: number): React.CSSProperties => {
  const c = clip.crop;
  if (!c) {
    return {width: '100%', height: '100%', objectFit: clip.fit ?? 'contain', transform: `scale(${zoom})`};
  }
  const srcW = c.sourceWidth ?? 1920;
  const srcH = c.sourceHeight ?? 1080;
  const s = Math.max(w / c.w, h / c.h) * zoom;
  return {
    position: 'absolute',
    width: srcW * s,
    height: srcH * s,
    maxWidth: 'none',
    left: w / 2 - (c.x + c.w / 2) * s,
    top: h / 2 - (c.y + c.h / 2) * s,
  };
};

const ClipView: React.FC<{clip: Clip; frames: number; w: number; h: number}> = ({clip, frames, w, h}) => {
  const frame = useCurrentFrame();
  const src = staticFile(clip.file);
  if (isImage(clip.file)) {
    // Stills get a very slow push-in so they do not read as frozen.
    const zoom = interpolate(frame, [0, frames], [1, 1.035], clamp);
    return (
      <AbsoluteFill style={{background: '#f1f3f6'}}>
        <Img src={src} style={mediaStyle(clip, w, h, zoom)} />
      </AbsoluteFill>
    );
  }
  return (
    <AbsoluteFill style={{background: '#000'}}>
      <OffthreadVideo
        src={src}
        muted
        trimBefore={Math.round((clip.startFrom ?? 0) * FPS)}
        playbackRate={clip.playbackRate ?? 1}
        style={mediaStyle(clip, w, h, 1)}
      />
    </AbsoluteFill>
  );
};

/** Plays every clip of a section back to back inside a screen of the given size. */
export const Footage: React.FC<{section: TimelineSection; screenWidth: number; aspect?: number}> = ({
  section,
  screenWidth,
  aspect = 16 / 9,
}) => (
  <Series>
    {section.clips.map((c, i) => {
      const frames = clipFrames(c);
      return (
        <Series.Sequence key={i} durationInFrames={frames} premountFor={30}>
          <ClipView clip={c} frames={frames} w={screenWidth} h={Math.round(screenWidth / aspect)} />
        </Series.Sequence>
      );
    })}
  </Series>
);

/** Screen aspect for a section: the first clip's crop, else 16:9. */
export const sectionAspect = (section: TimelineSection) => {
  const c = section.clips[0]?.crop;
  return c ? c.w / c.h : 16 / 9;
};

/** Small pill stating what the footage is (recording, speed). Fades when the text changes. */
export const FootageBadge: React.FC<{section: TimelineSection; size?: number}> = ({
  section,
  size = 22,
}) => {
  const frame = useCurrentFrame();
  let acc = 0;
  let current = section.clips[0];
  let localStart = 0;
  for (const c of section.clips) {
    const f = clipFrames(c);
    if (frame >= acc) {
      current = c;
      localStart = acc;
    }
    acc += f;
  }
  const text = clipBadge(current);
  const prevText = clipBadge(
    section.clips[Math.max(0, section.clips.indexOf(current) - 1)] ?? current,
  );
  const changed = prevText !== text || localStart === 0;
  const o = changed ? interpolate(frame - localStart, [0, 12], [0, 1], {...clamp, easing: EASE}) : 1;
  if (!text) return null;
  return (
    <div
      style={{
        ...fontBase,
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.45,
        fontSize: size,
        fontWeight: 600,
        color: C.text,
        padding: `${size * 0.32}px ${size * 0.7}px`,
        borderRadius: 999,
        background: 'rgba(18,28,48,0.9)',
        border: `1px solid ${C.lineStrong}`,
        opacity: o,
        whiteSpace: 'nowrap',
      }}
    >
      <span
        style={{
          width: size * 0.42,
          height: size * 0.42,
          borderRadius: 999,
          background: isImage(current.file) ? C.muted : '#e0524a',
          opacity: isImage(current.file) ? 1 : 0.6 + 0.4 * Math.abs(Math.sin(frame / 12)),
        }}
      />
      {text}
    </div>
  );
};
