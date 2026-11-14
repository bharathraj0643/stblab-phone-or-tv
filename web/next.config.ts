import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Ship browser-only assets in the Android app. All API calls remain relative to
  // the BPL TV origin and are handled by its embedded Ktor server.
  output: "export",
  trailingSlash: true,
  images: { unoptimized: true },
};

export default nextConfig;
