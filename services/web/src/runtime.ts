export type PublicContour = 'demo' | 'work';

const rawContour = import.meta.env.VITE_PUBLIC_CONTOUR?.trim().toLowerCase();

export const publicContour: PublicContour = rawContour === 'work' ? 'work' : 'demo';
export const demoContourEnabled = publicContour === 'demo';
export const workContourEnabled = publicContour === 'work';

export const serviceWorkerEnabled = import.meta.env.VITE_ENABLE_SW === 'true';

