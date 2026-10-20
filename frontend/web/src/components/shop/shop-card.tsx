import { ArrowRight, MapPin } from '@phosphor-icons/react';
import { Link } from 'react-router-dom';
import type { Shop } from '../../types';
import { distanceText, imagesOf } from '../../lib/utils';
import { Photo, Rating } from '../ui';

export function ShopCard({ shop, row = false }: { shop: Shop; row?: boolean }) {
  return <Link className={`shop-card ${row ? 'shop-row' : ''}`} to={`/shops/${shop.id}`}>
    <div className="shop-cover"><Photo src={imagesOf(shop.images)[0]} alt={`${shop.name}的商家照片`} thumbnail /></div>
    <div className="shop-card-body">
      <div className="shop-card-top"><span>{shop.area || '本地商家'}</span>{shop.distance !== undefined && <span>{distanceText(shop.distance)}</span>}</div>
      <h3>{shop.name}</h3>
      <Rating score={shop.score} comments={shop.comments} compact={!row} />
      {row && <p className="shop-row-address"><MapPin size={16} />{shop.address}</p>}
      <div className="shop-card-bottom"><span>{shop.avgPrice !== undefined ? <><strong>¥{shop.avgPrice}</strong><span className="muted"> / 人</span></> : <span className="muted">人均价格待补充</span>}</span><span className="shop-card-cta">查看商家 <ArrowRight size={16} /></span></div>
    </div>
  </Link>;
}
