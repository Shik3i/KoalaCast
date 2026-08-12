export interface NetworkConnectionInfo {
	saveData?: boolean;
	effectiveType?: string;
	type?: string;
}

/** Unknown connectivity cannot satisfy a Wi-Fi-only automatic-download promise. */
export function blocksWifiOnlyAutoDownload(connection?: NetworkConnectionInfo): boolean {
	if (!connection || connection.saveData) return true;
	if (connection.type) return connection.type === 'cellular';
	return connection.effectiveType === '2g' || connection.effectiveType === 'slow-2g';
}
