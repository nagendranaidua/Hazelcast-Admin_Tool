// Hazelcast SVG Logo as a React component (from screenshot style)
import React from 'react';

const HazelcastLogo = ({ width = 120, height = 48 }) => (
  <svg width={width} height={height} viewBox="0 0 120 48" fill="none" xmlns="http://www.w3.org/2000/svg">
    <rect x="0" y="0" width="48" height="48" rx="8" fill="#23235B"/>
    <rect x="56" y="0" width="16" height="48" rx="8" fill="#23235B"/>
    <rect x="80" y="0" width="40" height="16" rx="8" fill="#4B8DF8"/>
    <rect x="80" y="32" width="40" height="16" rx="8" fill="#4B8DF8"/>
  </svg>
);

export default HazelcastLogo;

