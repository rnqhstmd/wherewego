// mapbg.jsx — Illustrated city map (Seoul Seongsu-ish) + Globe for login
// Used as background in all map screens

/* ── Fixed pin positions (shared across all screens) ───── */
const MAP_PINS = {
  place:  [[180,260],[520,200],[750,310],[150,440],[680,480],[890,350]],
  memory: [[360,140],[660,270],[300,510],[580,550]],
  clusters: [[420,360,3],[800,170,2]],
};

function MapBg({
  w = '100%', h = '100%',
  dimmed = false,       // dims map (for bottom-sheet state)
  highlightPin = null,  // [x,y] — puts a ring on a specific pin
  crosshair = false,    // shows crosshair for add-pin mode
  newPinXY = null,      // [x,y] — red dot for newly placed pin
  panelOffset = 0,      // left-shift so content doesn't hide under panel
  zoomedIn = false,     // splits clusters back into individual pins
}) {
  // Pins that, when zoomedIn, replace each cluster bubble.
  // Each item: [x, y, type]
  const CLUSTER_KIDS = zoomedIn ? [
    // Cluster [420,360,3] splits into:
    [400, 348, 'place'],
    [438, 352, 'memory'],
    [422, 380, 'place'],
    // Cluster [800,170,2] splits into:
    [790, 158, 'place'],
    [814, 178, 'memory'],
  ] : [];
  return (
    <svg width={w} height={h} viewBox="0 0 1000 800"
      preserveAspectRatio="xMidYMid slice"
      style={{ display: 'block', userSelect: 'none' }}>

      {/* Base warm beige */}
      <rect width="1000" height="800" fill="#EAE4D4"/>

      {/* Han River — bottom */}
      <path d="M0 680 Q250 650 500 670 Q750 690 1000 660 L1000 800 L0 800 Z"
        fill="#D4E8F0" opacity=".85"/>
      {/* River shimmer */}
      <path d="M100 720 Q300 705 500 715 Q700 725 900 710"
        fill="none" stroke="white" strokeWidth="2" opacity=".4"/>

      {/* Riverside park strip */}
      <path d="M0 660 Q250 640 500 656 Q750 672 1000 648 L1000 680 Q750 690 500 670 Q250 650 0 680 Z"
        fill="#D5E5CB" opacity=".7"/>

      {/* Major horizontal roads */}
      <rect x="0" y="194" width="1000" height="16" fill="white" opacity=".95"/>
      <rect x="0" y="455" width="1000" height="14" fill="white" opacity=".95"/>
      {/* Major vertical roads */}
      <rect x="246" y="0" width="13" height="800" fill="white" opacity=".95"/>
      <rect x="548" y="0" width="13" height="800" fill="white" opacity=".95"/>
      <rect x="800" y="0" width="13" height="800" fill="white" opacity=".95"/>

      {/* Minor roads */}
      <rect x="0" y="320" width="1000" height="8" fill="white" opacity=".7"/>
      <rect x="380" y="0" width="8" height="800" fill="white" opacity=".65"/>
      <rect x="660" y="0" width="8" height="800" fill="white" opacity=".55"/>

      {/* City blocks — varying shades */}
      {/* NW quadrant */}
      <rect x="10" y="10"  width="230" height="178" rx="2" fill="#EEE8DA" opacity=".7"/>
      <rect x="10" y="10"  width="105" height="80"  rx="2" fill="#F2EDE3" opacity=".6"/>
      <rect x="120" y="10" width="120" height="80"  rx="2" fill="#EDE7D8" opacity=".6"/>
      {/* NE quadrant row 1 */}
      <rect x="264" y="10"  width="108" height="178" rx="2" fill="#EEE8DA" opacity=".65"/>
      <rect x="390" y="10"  width="152" height="108" rx="2" fill="#F0EAE0" opacity=".6"/>
      <rect x="390" y="124" width="152" height="64"  rx="2" fill="#ECEADF" opacity=".55"/>
      <rect x="562" y="10"  width="230" height="85"  rx="2" fill="#EDE7D8" opacity=".65"/>
      <rect x="562" y="100" width="230" height="88"  rx="2" fill="#EAE3D5" opacity=".6"/>
      <rect x="820" y="10"  width="175" height="178" rx="2" fill="#EEE8DA" opacity=".6"/>
      {/* Mid band NW */}
      <rect x="10"  y="212" width="230" height="103" rx="2" fill="#EDE7DA" opacity=".65"/>
      <rect x="264" y="212" width="108" height="103" rx="2" fill="#F1EBDF" opacity=".6"/>
      <rect x="390" y="212" width="152" height="103" rx="2" fill="#EAE4D6" opacity=".6"/>
      {/* Park */}
      <rect x="562" y="212" width="130" height="103" rx="4" fill="#D5E5CB" opacity=".8"/>
      <rect x="700" y="212" width="94"  height="103" rx="2" fill="#EEE8DA" opacity=".6"/>
      <rect x="820" y="212" width="175" height="103" rx="2" fill="#EDE7D8" opacity=".6"/>
      {/* Mid band after minor road */}
      <rect x="10"  y="334" width="230" height="115" rx="2" fill="#EEE8DA" opacity=".6"/>
      <rect x="264" y="334" width="108" height="115" rx="2" fill="#EDE7DA" opacity=".6"/>
      <rect x="390" y="334" width="152" height="115" rx="2" fill="#F0EAE0" opacity=".6"/>
      <rect x="562" y="334" width="91"  height="115" rx="2" fill="#EAE4D6" opacity=".6"/>
      <rect x="675" y="334" width="119" height="115" rx="2" fill="#EDE7D8" opacity=".6"/>
      <rect x="820" y="334" width="175" height="115" rx="2" fill="#EEE8DA" opacity=".6"/>
      {/* Lower band */}
      <rect x="10"  y="474" width="230" height="170" rx="2" fill="#EEE8DA" opacity=".6"/>
      <rect x="264" y="474" width="108" height="170" rx="2" fill="#EDE7DA" opacity=".6"/>
      <rect x="390" y="474" width="152" height="80"  rx="2" fill="#EEE8DA" opacity=".6"/>
      <rect x="390" y="560" width="152" height="84"  rx="2" fill="#EAE4D6" opacity=".55"/>
      <rect x="562" y="474" width="230" height="80"  rx="2" fill="#EDE7D8" opacity=".6"/>
      <rect x="562" y="560" width="230" height="84"  rx="2" fill="#EEE8DA" opacity=".55"/>
      <rect x="820" y="474" width="175" height="170" rx="2" fill="#EDE7DA" opacity=".6"/>

      {/* Subtle grid overlay */}
      <defs>
        <pattern id="mapGrid" width="40" height="40" patternUnits="userSpaceOnUse">
          <path d="M40 0H0V40" fill="none" stroke="white" strokeWidth=".3" opacity=".5"/>
        </pattern>
      </defs>
      <rect width="1000" height="800" fill="url(#mapGrid)"/>

      {/* Dim overlay for bottom-sheet states */}
      {dimmed && <rect width="1000" height="800" fill="#1A1A2E" opacity=".35"/>}

      {/* ── Pins ── */}
      {/* Place pins ● */}
      {MAP_PINS.place.map(([x, y], i) => {
        const isHL = highlightPin && highlightPin[0] === x && highlightPin[1] === y;
        return (
          <g key={`p${i}`} transform={`translate(${x} ${y})`}>
            {isHL && <circle r="14" fill={T.pinPlace} opacity=".25"/>}
            <circle r={isHL ? 8 : 6} fill={T.pinPlace}
              style={{ filter: `drop-shadow(0 1px 4px ${T.pinPlace}80)` }}/>
            {isHL && <circle r="6" fill="none" stroke={T.pinPlace} strokeWidth="2.5"/>}
          </g>
        );
      })}
      {/* Memory pins ♡ — heart shape */}
      {MAP_PINS.memory.map(([x, y], i) => {
        const isHL = highlightPin && highlightPin[0] === x && highlightPin[1] === y;
        const s   = isHL ? 1.3 : 1;
        return (
          <g key={`m${i}`} transform={`translate(${x} ${y}) scale(${s})`}>
            {isHL && <circle r="12" fill={T.pinMemory} opacity=".25"/>}
            {/* Heart path centered on (0,0), roughly 14×12 */}
            <path d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z"
              fill={T.pinMemory}
              style={{ filter: `drop-shadow(0 1.5px 4px ${T.pinMemory}90)` }}/>
          </g>
        );
      })}
      {/* Clusters — shown only when NOT zoomed in */}
      {!zoomedIn && MAP_PINS.clusters.map(([x, y, n], i) => (
        <g key={`c${i}`} transform={`translate(${x} ${y})`}>
          {/* outer halo */}
          <circle r="22" fill="#C4622D" opacity=".15"/>
          <circle r="18" fill="#C4622D" opacity=".22"/>
          {/* core */}
          <circle r="15" fill="#C4622D"
            style={{ filter: 'drop-shadow(0 2px 6px rgba(196,98,45,0.4))' }}/>
          <text textAnchor="middle" dominantBaseline="central"
            fontFamily={F.sans} fontSize="13" fontWeight="700" fill="white">{n}</text>
        </g>
      ))}
      {/* When zoomed in: cluster children appear as individual pins */}
      {zoomedIn && CLUSTER_KIDS.map(([x, y, type], i) => {
        if (type === 'memory') {
          return (
            <g key={`kk${i}`} transform={`translate(${x} ${y})`}>
              <path d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z"
                fill={T.pinMemory}
                style={{ filter: `drop-shadow(0 1.5px 4px ${T.pinMemory}90)` }}/>
            </g>
          );
        }
        return (
          <circle key={`kk${i}`} cx={x} cy={y} r="6" fill={T.pinPlace}
            style={{ filter: `drop-shadow(0 1px 4px ${T.pinPlace}80)` }}/>
        );
      })}

      {/* New pin (red crosshair) */}
      {crosshair && (
        <g transform="translate(500 400)">
          <line x1="-16" y1="0" x2="16" y2="0" stroke={T.pinNew} strokeWidth="2" strokeDasharray="3 2"/>
          <line x1="0" y1="-16" x2="0" y2="16" stroke={T.pinNew} strokeWidth="2" strokeDasharray="3 2"/>
          <circle r="5" fill={T.pinNew} style={{ filter: 'drop-shadow(0 2px 5px rgba(224,90,90,0.5))' }}/>
        </g>
      )}
      {newPinXY && (
        <circle cx={newPinXY[0]} cy={newPinXY[1]} r="7" fill={T.pinNew}
          style={{ filter: 'drop-shadow(0 2px 6px rgba(224,90,90,0.6))' }}/>
      )}
    </svg>
  );
}

/* ── Illustrated globe for login screen ─────────────────── */
function GlobeBg({ w = 600, h = 600, style = {} }) {
  return (
    <svg width={w} height={h} viewBox="0 0 600 600" style={style}>
      {/* glow */}
      <radialGradient id="globeGlow" cx="50%" cy="50%" r="50%">
        <stop offset="0%"   stopColor="#D4E8F0" stopOpacity=".6"/>
        <stop offset="60%"  stopColor="#EAE4D4" stopOpacity=".3"/>
        <stop offset="100%" stopColor="#FAF8F5" stopOpacity="0"/>
      </radialGradient>
      <circle cx="300" cy="300" r="290" fill="url(#globeGlow)"/>
      {/* sphere outline */}
      <circle cx="300" cy="300" r="220" fill="#EEE8DC" opacity=".5"
        stroke="#D8D0C0" strokeWidth="1.5"/>
      {/* equator */}
      <ellipse cx="300" cy="300" rx="220" ry="85"
        fill="none" stroke="#C8C0B0" strokeWidth="1.2" opacity=".6"/>
      {/* meridians */}
      <ellipse cx="300" cy="300" rx="100" ry="220"
        fill="none" stroke="#C8C0B0" strokeWidth="1" opacity=".4"/>
      <ellipse cx="300" cy="300" rx="185" ry="220"
        fill="none" stroke="#C8C0B0" strokeWidth=".8" opacity=".3"/>
      {/* land blobs */}
      <path d="M220 220 C250 205 280 215 300 235 C320 255 345 248 358 236
               C370 224 375 236 370 250 C360 268 340 275 320 268
               C300 260 275 272 262 262 C248 252 218 240 220 220 Z"
        fill="#D8D0B8" opacity=".7"/>
      <path d="M260 340 C278 332 298 340 308 352 C318 364 312 376 295 376
               C275 376 252 364 260 340 Z"
        fill="#D8D0B8" opacity=".6"/>
      {/* pin dots on globe */}
      <circle cx="295" cy="230" r="5" fill={T.pinMemory} opacity=".8"/>
      <circle cx="335" cy="260" r="4" fill={T.pinPlace}  opacity=".8"/>
      <circle cx="265" cy="355" r="4" fill={T.pinPlace}  opacity=".7"/>
    </svg>
  );
}

Object.assign(window, { MapBg, GlobeBg, MAP_PINS });
