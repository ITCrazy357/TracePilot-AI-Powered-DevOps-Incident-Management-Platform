export function Brand() {
  return (
    <span className="brand">
      <svg
        className="brand-mark"
        width="32"
        height="32"
        viewBox="0 0 32 32"
        fill="none"
        aria-hidden="true"
      >
        <path
          d="M6 24V14a6 6 0 0 1 6-6h14M6 24h12a6 6 0 0 0 6-6V8"
          stroke="#0297DE"
          strokeWidth="2.5"
          strokeLinecap="round"
        />
        <circle cx="6" cy="24" r="4" fill="#00618E" />
        <circle cx="25" cy="8" r="4" fill="#0297DE" />
        <circle cx="8" cy="10" r="3.5" fill="#D83995" stroke="white" strokeWidth="2" />
      </svg>
      <span>
        TracePilot<span className="brand-dot">.</span>
      </span>
    </span>
  );
}
