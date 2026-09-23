export const MEASURES = [
  { id: "M1", name: "Выделенные полосы для автобусов", scope: "Район" },
  { id: "M2", name: "Умные светофоры (адаптивное управление)", scope: "Город" },
  { id: "M3", name: "Линия ЛРТ / расширение", scope: "Район" },
  { id: "M4", name: "Парк / сквер", scope: "Район" },
  { id: "M5", name: "Перевод частного сектора на чистое топливо", scope: "Район" },
  { id: "M6", name: "Городская программа озеленения и ветрозащитных полос", scope: "Город" },
  { id: "M7", name: "Школа + детсад (модульное строительство)", scope: "Район" },
  { id: "M8", name: "Центр семейного здоровья / поликлиника", scope: "Район" },
  { id: "M9", name: "Дворовые спорт-хабы", scope: "Район" },
  { id: "M10", name: "Освещение и камеры (расширение Safe City)", scope: "Район" },
  { id: "M11", name: "Безопасные переходы и школьные зоны", scope: "Район" },
  { id: "M12", name: "Единая цифровая платформа обращений", scope: "Город" },
  { id: "M13", name: "Модернизация тепло- и водосетей", scope: "Район" },
  { id: "M14", name: "Аварийные бригады ЖКХ + раннее оповещение", scope: "Город" },
] as const;

export type MeasureId = (typeof MEASURES)[number]["id"];
export const isMeasureId = (id: string): id is MeasureId =>
  MEASURES.some((measure) => measure.id === id);
export const isCityMeasure = (id: MeasureId) =>
  MEASURES.find((measure) => measure.id === id)?.scope === "Город";
