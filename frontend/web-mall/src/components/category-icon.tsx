import { Balloon, Barbell, Confetti, FlowerLotus, ForkKnife, HandHeart, MicrophoneStage, PaintBrush, Scissors, Storefront, Wine, type Icon } from '@phosphor-icons/react';
const icons: Record<string, Icon> = { '1': ForkKnife, '2': MicrophoneStage, '3': Scissors, '4': Barbell, '5': HandHeart, '6': FlowerLotus, '7': Balloon, '8': Wine, '9': Confetti, '10': PaintBrush };
export function CategoryIcon({ id, size = 21 }: { id: string | number; size?: number }) {
  const Component = icons[String(id)] || Storefront;
  return <Component size={size} weight="regular" aria-hidden="true" />;
}
