import DistrictMap from "./district-map";

export default function Home() {
  return (
    <main className="page-shell">
      <header className="page-header">
        <p className="eyebrow">АСТАНА · ИНТЕРАКТИВНАЯ КАРТА</p>
        <h1>Районы города</h1>
        <p className="page-intro">Нажмите на район на карте, чтобы увидеть его название и границы.</p>
      </header>
      <DistrictMap />
      <footer className="page-footer">Границы районов: открытый слой «Районы» геопортала Астаны.</footer>
    </main>
  );
}
