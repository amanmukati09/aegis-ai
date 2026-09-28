/** @type {import('next').NextConfig} */
const GATEWAY_INTERNAL = process.env.GATEWAY_INTERNAL_URL ?? "http://gateway:8080";

const nextConfig = {
  // Emit a minimal standalone server for a small Docker runtime image.
  output: "standalone",
  reactStrictMode: true,
  // ESLint isn't configured in this skeleton; don't fail the production build on it.
  eslint: { ignoreDuringBuilds: true },
  // Proxy browser API calls through the Next origin to the gateway. The browser
  // only ever talks to the Next server (no CORS, works through an SSH tunnel);
  // Next forwards to the gateway by its container name.
  async rewrites() {
    return [
      {
        source: "/api/gateway/:path*",
        destination: `${GATEWAY_INTERNAL}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
