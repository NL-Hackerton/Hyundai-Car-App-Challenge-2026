import {getStaticFiles, staticFile} from 'remotion';
import {FPS} from './theme';

/**
 * Footage timeline, loaded at render time from public/demo/timeline.json.
 * Times are in seconds. See README.md for the schema.
 */
export type Clip = {
  /** Path relative to public/, e.g. "demo/01-charging.mp4". Images (png/jpg/webp) are held still. */
  file: string;
  /** Source in-point (s). Default 0. */
  startFrom?: number;
  /** Source out-point (s). For images: hold duration in "source seconds". */
  endAt: number;
  /** Speed multiplier. On-screen duration = (endAt - startFrom) / playbackRate. Default 1. */
  playbackRate?: number;
  /** Corner badge text. Omit to auto-generate from playbackRate; "" hides the badge. */
  label?: string;
  /** How the display is filled when not cropped. Default "contain". */
  fit?: 'contain' | 'cover';
  /**
   * Optional crop rectangle in source pixels (source defaults to 1920×1080).
   * The crop is scaled to cover the screen; a section's screen takes its first clip's crop aspect.
   */
  crop?: {x: number; y: number; w: number; h: number; sourceWidth?: number; sourceHeight?: number};
};

export type Annotation = {
  /** Seconds from the start of the section (on-screen time, after playbackRate). */
  at: number;
  title: string;
  body: string;
};

export type TimelineSection = {
  clips: Clip[];
  annotations: Annotation[];
};

export type Timeline = TimelineSection & {
  /** Optional separate cut for the vertical reel. If absent, the main section is time-compressed. */
  reel?: TimelineSection;
};

export const REEL_DEMO_FALLBACK_SECONDS = 26;

export const isImage = (file: string) => /\.(png|jpe?g|webp)$/i.test(file);

export const clipFrames = (c: Clip) =>
  Math.max(1, Math.round(((c.endAt - (c.startFrom ?? 0)) / (c.playbackRate ?? 1)) * FPS));

export const sectionFrames = (s: TimelineSection) =>
  s.clips.reduce((sum, c) => sum + clipFrames(c), 0);

export const clipBadge = (c: Clip): string => {
  if (c.label !== undefined) return c.label;
  if (isImage(c.file)) return '화면 시안';
  const rate = c.playbackRate ?? 1;
  if (Math.abs(rate - 1) < 0.01) return '실제 화면 녹화';
  const r = Number.isInteger(rate) ? String(rate) : rate.toFixed(1);
  return `실제 화면 녹화 · ${r}배속`;
};

const assertSection = (s: unknown, where: string): TimelineSection => {
  const sec = s as TimelineSection;
  if (!sec || !Array.isArray(sec.clips) || sec.clips.length === 0) {
    throw new Error(`timeline.json: ${where}.clips must be a non-empty array`);
  }
  if (!Array.isArray(sec.annotations)) {
    throw new Error(`timeline.json: ${where}.annotations must be an array`);
  }
  sec.clips.forEach((c, i) => {
    if (typeof c.file !== 'string' || typeof c.endAt !== 'number') {
      throw new Error(`timeline.json: ${where}.clips[${i}] needs "file" (string) and "endAt" (number)`);
    }
    if (c.endAt <= (c.startFrom ?? 0)) {
      throw new Error(`timeline.json: ${where}.clips[${i}] endAt must be greater than startFrom`);
    }
    const k = c.crop;
    if (k && !(k.w > 0 && k.h > 0 && k.x >= 0 && k.y >= 0 &&
      k.x + k.w <= (k.sourceWidth ?? 1920) && k.y + k.h <= (k.sourceHeight ?? 1080))) {
      throw new Error(`timeline.json: ${where}.clips[${i}].crop must be a positive rectangle inside the source frame`);
    }
  });
  return {clips: sec.clips, annotations: [...sec.annotations].sort((a, b) => a.at - b.at)};
};

export const loadTimeline = async (): Promise<Timeline> => {
  const res = await fetch(staticFile('demo/timeline.json'));
  if (!res.ok) throw new Error(`Could not load public/demo/timeline.json (HTTP ${res.status})`);
  const json = (await res.json()) as Timeline;
  const main = assertSection(json, 'root');
  return {...main, reel: json.reel ? assertSection(json.reel, 'reel') : undefined};
};

/** Reel section: explicit `reel` cut, or the main cut compressed to the fallback length. */
export const reelSection = (t: Timeline): TimelineSection => {
  if (t.reel) return t.reel;
  const factor = sectionFrames(t) / (REEL_DEMO_FALLBACK_SECONDS * FPS);
  return {
    clips: t.clips.map((c) => ({
      ...c,
      playbackRate: (c.playbackRate ?? 1) * factor,
      label: c.label !== undefined && isImage(c.file) ? c.label : undefined,
    })),
    annotations: t.annotations.map((a) => ({...a, at: a.at / factor})),
  };
};

export const activeAnnotation = (anns: Annotation[], seconds: number) => {
  let idx = 0;
  for (let i = 0; i < anns.length; i++) if (anns[i].at <= seconds) idx = i;
  return idx;
};

/** Background music file under public/audio/, or null when absent. */
export const findMusic = (base: 'bgm-demo' | 'bgm-reel'): string | null => {
  const names = new Set(getStaticFiles().map((f) => f.name.replace(/\\/g, '/')));
  for (const ext of ['mp3', 'wav', 'm4a']) {
    const n = `audio/${base}.${ext}`;
    if (names.has(n)) return n;
  }
  return null;
};
