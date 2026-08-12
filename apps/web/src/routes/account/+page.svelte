<script lang="ts">
	import { t } from '$lib/i18n';
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { toast } from '$lib/stores/toast.svelte';
	import { activateAccountContext, resetAllLocalData } from '$lib/stores/account-context';
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { sync } from '$lib/stores/sync.svelte';

	interface UserSession {
		id: string;
		kind: 'session' | 'device';
		device_name: string;
		device_type: string;
		truncated_ip: string;
		sanitized_user_agent: string;
		created_at: number;
		last_used_at: number;
		is_current?: boolean;
	}

	interface UserProfile {
		user_id: string;
		username: string;
		role: string;
	}

	let user = $state<UserProfile | null>(null);
	let sessions = $state<UserSession[]>([]);
	let isLoading = $state(true);

	onMount(async () => {
		try {
			const res = await fetch('/api/v1/auth/status');
			if (!res.ok) return;
			const status = await res.json();
			if (!status.authenticated || !status.user_id) return;
			user = status;
			await loadSessions();
		} catch (_) {
			// The deletion explanation is deliberately public and remains useful when
			// authentication status cannot be loaded.
		} finally {
			isLoading = false;
		}
	});

	async function loadSessions() {
		try {
			const res = await fetch('/api/v1/auth/sessions');
			if (res.ok) {
				const data = await res.json();
				sessions = data.sessions || [];
			}
		} catch (_) {}
	}

	async function revokeSession(id: string) {
		try {
			const res = await fetch(`/api/v1/auth/sessions/${id}`, { method: 'DELETE' });
			if (res.ok) {
				sessions = sessions.filter((s) => s.id !== id);
				toast.success(t('toast.sessionRevoked'));
			}
		} catch (_) {
			toast.error(t('toast.sessionRevokeError'));
		}
	}

	async function handleLogout() {
		try {
			const res = await fetch('/api/v1/auth/logout', { method: 'POST' });
			if (!res.ok) throw new Error(`logout ${res.status}`);
		} catch (_) {
			toast.error(t('toast.signOutError'));
			return;
		}
		await activateAccountContext(null);
		toast.success(t('toast.signedOut'));
		goto('/login');
	}

	// ---- Export and deletion ----
	let deleteCredential = $state('');
	let isDeleting = $state(false);
	let deleteError = $state('');
	let showDeleteForm = $state(false);
	let deleteAccountButton = $state<HTMLButtonElement>();
	let deleteCredentialInput = $state<HTMLInputElement>();
	let dataCredential = $state('');
	let isDeletingData = $state(false);
	let dataDeleteError = $state('');
	let showDataDeleteForm = $state(false);
	let deleteDataButton = $state<HTMLButtonElement>();
	let dataCredentialInput = $state<HTMLInputElement>();

	async function openDeleteForm() {
		showDeleteForm = true;
		await tick();
		deleteCredentialInput?.focus();
	}

	async function closeDeleteForm() {
		showDeleteForm = false;
		deleteCredential = '';
		deleteError = '';
		await tick();
		deleteAccountButton?.focus();
	}

	async function openDataDeleteForm() {
		showDataDeleteForm = true;
		await tick();
		dataCredentialInput?.focus();
	}

	async function closeDataDeleteForm() {
		showDataDeleteForm = false;
		dataCredential = '';
		dataDeleteError = '';
		await tick();
		deleteDataButton?.focus();
	}

	async function deleteSynchronizedData(event: Event) {
		event.preventDefault();
		if (!user || !dataCredential.trim() || isDeletingData) return;
		if (!(await confirmDialog.ask(t('account.deleteDataConfirm')))) return;

		isDeletingData = true;
		dataDeleteError = '';
		let serverDeleted = false;
		try {
			const credential = dataCredential.trim();
			const res = await fetch('/api/v1/auth/data', {
				method: 'DELETE',
				headers: { 'Content-Type': 'application/json' },
				// Passwords may contain dashes, so the server tries the same opaque
				// credential against both verifiers.
				body: JSON.stringify({ password: credential, recovery_code: credential })
			});
			if (res.status === 401) {
				dataDeleteError = t('account.deleteInvalidCredential');
				return;
			}
			if (!res.ok) {
				dataDeleteError = t('account.deleteDataFailed');
				return;
			}
			serverDeleted = true;
			const result = await res.json();
			if (!Number.isSafeInteger(result.data_generation) || result.data_generation < 0) {
				dataDeleteError = t('account.deleteDataFailed');
				return;
			}
			await sync.acceptDataReset(user.user_id, result.data_generation);
			showDataDeleteForm = false;
			toast.success(t('account.deleteDataDone'));
		} catch (_) {
			dataDeleteError = t(serverDeleted ? 'account.deleteDataLocalFailed' : 'account.deleteDataOffline');
		} finally {
			isDeletingData = false;
			dataCredential = '';
		}
	}

	function exportAccountData() {
		// A plain navigation, not fetch + blob: the response already carries a
		// Content-Disposition and can be large, so the browser streams it to disk
		// instead of the page holding the whole export in memory.
		window.location.href = '/api/v1/auth/export';
	}

	async function deleteAccount(event: Event) {
		event.preventDefault();
		if (!deleteCredential.trim() || isDeleting) return;
		if (!(await confirmDialog.ask(t('account.deleteConfirm')))) return;

		isDeleting = true;
		deleteError = '';
		try {
			const res = await fetch('/api/v1/auth/account', {
				method: 'DELETE',
				headers: { 'Content-Type': 'application/json' },
				// The same field serves both: a recovery code is the way back in when
				// the password is what got lost, so it has to work here too.
				body: JSON.stringify({
					password: deleteCredential.trim(),
					recovery_code: deleteCredential.trim()
				})
			});
			if (res.status === 401) {
				deleteError = t('account.deleteInvalidCredential');
				return;
			}
			if (!res.ok && res.status !== 204) {
				deleteError = t('account.deleteFailed');
				return;
			}
			// The server rows are gone; the copy in this browser has to follow, or the
			// next visit silently re-syncs a deleted account's library back into view.
			await resetAllLocalData();
			await activateAccountContext(null);
			toast.success(t('account.deleteDone'));
			goto('/');
		} catch (_) {
			deleteError = t('account.deleteFailed');
		} finally {
			isDeleting = false;
			deleteCredential = '';
		}
	}

	function formatDate(timestampMs: number): string {
		if (!timestampMs) return t('account.unknown');
		return new Date(timestampMs).toLocaleString(undefined, {
			dateStyle: 'medium',
			timeStyle: 'short'
		});
	}
</script>

<div class="account-page">
	<header class="page-header">
		<h1><i class="ph ph-user-gear" aria-hidden="true"></i> {t('account.title')}</h1>
		<p class="subtitle">{t('account.subtitle')}</p>
	</header>

	<section class="card public-data-card" aria-labelledby="data-deletion-title">
		<header class="public-data-header">
			<h2 id="data-deletion-title"><i class="ph ph-shield-check" aria-hidden="true"></i> {t('account.publicDeletionTitle')}</h2>
			<p class="subtitle">{t('account.publicDeletionIntro')}</p>
		</header>
		<div class="deletion-options">
			<article>
				<h3>{t('account.deleteData')}</h3>
				<p>{t('account.deleteDataPublicSummary')}</p>
				<h4>{t('account.stepsTitle')}</h4>
				<ol>
					<li>{t('account.deleteDataStep1')}</li>
					<li>{t('account.deleteDataStep2')}</li>
					<li>{t('account.deleteDataStep3')}</li>
				</ol>
				<h4>{t('account.deletedTitle')}</h4>
				<p>{t('account.deleteDataDeleted')}</p>
				<h4>{t('account.keptTitle')}</h4>
				<p>{t('account.deleteDataKept')}</p>
			</article>
			<article>
				<h3>{t('account.deleteAccount')}</h3>
				<p>{t('account.deleteAccountPublicSummary')}</p>
				<h4>{t('account.stepsTitle')}</h4>
				<ol>
					<li>{t('account.deleteAccountStep1')}</li>
					<li>{t('account.deleteAccountStep2')}</li>
					<li>{t('account.deleteAccountStep3')}</li>
				</ol>
				<h4>{t('account.deletedTitle')}</h4>
				<p>{t('account.deleteAccountDeleted')}</p>
				<h4>{t('account.keptTitle')}</h4>
				<p>{t('account.deleteAccountKept')}</p>
			</article>
		</div>
		<p class="retention-note"><i class="ph ph-clock" aria-hidden="true"></i> {t('account.securityLogRetention')}</p>
		{#if !isLoading && !user}
			<a class="btn btn-secondary public-sign-in" href="/login">{t('account.signInToExecute')}</a>
		{/if}
	</section>

	{#if isLoading}
		<div class="loading-state">
			<i class="ph ph-spinner spinner" aria-hidden="true"></i> {t('account.loadingProfile')}
		</div>
	{:else if user}
		<div class="account-grid">
			<!-- User Profile Card -->
			<section class="card profile-card">
				<div class="card-header">
					<div class="user-avatar">
						<i class="ph ph-user" aria-hidden="true"></i>
					</div>
					<div class="user-meta">
						<h2>{user.username}</h2>
						<span class="role-badge" class:admin={user.role === 'admin'}>
							{user.role}
						</span>
					</div>
				</div>

				<div class="profile-details">
					<div class="detail-item">
						<span class="detail-label"><i class="ph ph-arrows-clockwise" aria-hidden="true"></i> {t('account.cloudSync')}</span>
						<span class="detail-value active"><i class="ph ph-check-circle" aria-hidden="true"></i> {t('account.active')}</span>
					</div>
					<div class="detail-item">
						<span class="detail-label"><i class="ph ph-fingerprint" aria-hidden="true"></i> {t('account.accountId')}</span>
						<span class="detail-value mono">{user.user_id}</span>
					</div>
				</div>

				<div class="card-actions">
					{#if user.role === 'admin'}
						<a href="/admin" class="btn btn-secondary">
							<i class="ph ph-shield" aria-hidden="true"></i> {t('account.adminDashboard')}
						</a>
					{/if}
					<button type="button" class="btn btn-danger" onclick={handleLogout}>
						<i class="ph ph-sign-out" aria-hidden="true"></i> {t('account.signOut')}
					</button>
				</div>
			</section>

			<!-- Active Sessions Card -->
			<section class="card sessions-card">
				<header class="section-header">
					<h3><i class="ph ph-devices" aria-hidden="true"></i> {t('account.activeSessions')}</h3>
					<span class="session-count">{t('account.deviceCount', { count: sessions.length })}</span>
				</header>

				{#if sessions.length === 0}
					<p class="empty-text">{t('account.noSessions')}</p>
				{:else}
					<div class="sessions-list">
						{#each sessions as session (session.id)}
							<div class="session-row">
								<div class="session-icon">
									<i
										class="ph"
										class:ph-desktop-tower={session.kind !== 'device'}
										class:ph-device-mobile={session.kind === 'device'}
										aria-hidden="true"
									></i>
								</div>
								<div class="session-info">
									<div class="session-title">
										<span class="client-type">{session.device_name || session.device_type || t('account.browser')}</span>
										{#if session.is_current}
											<span class="current-badge">{t('account.currentDevice')}</span>
										{/if}
									</div>
									<div class="session-meta">
										<span>{t('account.ipSubnet')}: {session.truncated_ip || t('account.direct')}</span>
										<span>•</span>
										<span>{t('account.lastActive')}: {formatDate(session.last_used_at || session.created_at)}</span>
									</div>
								</div>
								{#if !session.is_current}
									<button
										type="button"
										class="btn-revoke"
										onclick={() => revokeSession(session.id)}
										title={t('settings.revokeSession')}
									>
										{t('common.revoke')}
									</button>
								{/if}
							</div>
						{/each}
					</div>
				{/if}
			</section>

			<!--
				Required before this app can ship on Google Play: an account that can be
				created in the app has to be deletable in the app. It is also simply the
				right half of the promise the privacy policy makes.
			-->
			<section class="card danger-card">
				<header class="data-control-header">
					<h3><i class="ph ph-warning-octagon" aria-hidden="true"></i> {t('account.dataControl')}</h3>
					<p class="subtitle">{t('account.dataControlHint')}</p>
				</header>

				<div class="card-actions">
					<button type="button" class="btn btn-secondary" onclick={exportAccountData}>
						<i class="ph ph-download-simple" aria-hidden="true"></i> {t('account.exportData')}
					</button>
					{#if !showDataDeleteForm}
						<button
							bind:this={deleteDataButton}
							type="button"
							class="btn btn-danger-soft"
							onclick={openDataDeleteForm}
						>
							<i class="ph ph-eraser" aria-hidden="true"></i> {t('account.deleteData')}
						</button>
					{/if}
					{#if !showDeleteForm}
						<button
							bind:this={deleteAccountButton}
							type="button"
							class="btn btn-danger"
							onclick={openDeleteForm}
						>
							<i class="ph ph-trash" aria-hidden="true"></i> {t('account.deleteAccount')}
						</button>
					{/if}
				</div>

				{#if showDataDeleteForm}
					<form class="delete-form" onsubmit={deleteSynchronizedData}>
						<p class="delete-warning">{t('account.deleteDataWarning')}</p>
						<label for="data-delete-credential">{t('account.deleteCredentialLabel')}</label>
						<input
							bind:this={dataCredentialInput}
							id="data-delete-credential"
							type="password"
							bind:value={dataCredential}
							placeholder={t('account.deleteCredentialPlaceholder')}
							autocomplete="current-password"
							required
						/>
						{#if dataDeleteError}<p class="delete-error" role="alert">{dataDeleteError}</p>{/if}
						<div class="card-actions">
							<button type="submit" class="btn btn-danger" disabled={!dataCredential.trim() || isDeletingData}>
								{isDeletingData ? t('account.deletingData') : t('account.deleteDataConfirmButton')}
							</button>
							<button type="button" class="btn btn-secondary" onclick={closeDataDeleteForm}>
								{t('common.cancel')}
							</button>
						</div>
					</form>
				{/if}

				{#if showDeleteForm}
					<form class="delete-form" onsubmit={deleteAccount}>
						<p class="delete-warning">{t('account.deleteWarning')}</p>
						<label for="delete-credential">{t('account.deleteCredentialLabel')}</label>
						<input
							bind:this={deleteCredentialInput}
							id="delete-credential"
							type="password"
							bind:value={deleteCredential}
							placeholder={t('account.deleteCredentialPlaceholder')}
							autocomplete="current-password"
							required
						/>
						{#if deleteError}<p class="delete-error" role="alert">{deleteError}</p>{/if}
						<div class="card-actions">
							<button type="submit" class="btn btn-danger" disabled={!deleteCredential.trim() || isDeleting}>
								{isDeleting ? t('account.deleting') : t('account.deleteConfirmButton')}
							</button>
							<button
								type="button"
								class="btn btn-secondary"
								onclick={closeDeleteForm}
							>
								{t('common.cancel')}
							</button>
						</div>
					</form>
				{/if}
			</section>
		</div>
	{/if}
</div>

<style>
	.account-page {
		width: 100%;
		max-width: 1080px;
		margin: 0 auto;
		padding-inline: clamp(1rem, 4vw, 2rem);
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.page-header h1 {
		font-size: 2rem;
		font-weight: 800;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.subtitle {
		color: var(--text-secondary);
		margin-top: 0.25rem;
		font-size: 0.95rem;
	}

	.loading-state {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		font-size: 1.05rem;
		color: var(--text-secondary);
		padding: 3rem 0;
	}

	.account-grid {
		display: grid;
		grid-template-columns: minmax(19rem, 0.9fr) minmax(0, 1.35fr);
		gap: 1.5rem;
		align-items: start;
	}

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 20px;
		padding: 1.75rem;
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.card-header {
		display: flex;
		align-items: center;
		gap: 1rem;
	}

	.public-data-card { border-color: color-mix(in srgb, var(--accent-green) 35%, var(--border-subtle)); }
	.public-data-header {
		display: grid;
		gap: 0.45rem;
		max-width: 48rem;
	}
	.public-data-header .subtitle { margin: 0; line-height: 1.55; }
	.public-data-header h2 {
		font-size: 1.35rem;
		font-weight: 800;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.55rem;
		margin: 0;
	}
	.deletion-options {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 1rem;
	}
	.deletion-options article {
		padding: 1.25rem;
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		background: var(--bg-elevated);
		min-width: 0;
	}
	.deletion-options h3, .deletion-options h4 { color: var(--text-primary); margin: 0 0 0.5rem; }
	.deletion-options h4 {
		font-size: 0.82rem;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		margin-top: 1rem;
	}
	.deletion-options p, .deletion-options ol {
		color: var(--text-secondary);
		font-size: 0.9rem;
		line-height: 1.55;
		margin: 0;
	}
	.deletion-options ol { padding-left: 1.25rem; }
	.retention-note {
		margin: 0;
		padding: 0.9rem 1rem;
		border-radius: 10px;
		background: color-mix(in srgb, var(--accent-green) 10%, transparent);
		color: var(--text-secondary);
		font-size: 0.9rem;
		line-height: 1.5;
	}
	.public-sign-in { align-self: flex-start; }

	.user-avatar {
		width: 3.5rem;
		height: 3.5rem;
		border-radius: 50%;
		background: color-mix(in srgb, var(--accent-green) 20%, transparent);
		color: var(--accent-green);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.8rem;
	}

	.user-meta h2 {
		font-size: 1.35rem;
		font-weight: 800;
		color: var(--text-primary);
		margin: 0;
		overflow-wrap: anywhere;
	}

	.user-meta,
	.session-info {
		min-width: 0;
	}

	.role-badge {
		display: inline-block;
		margin-top: 0.25rem;
		padding: 0.2rem 0.6rem;
		border-radius: 6px;
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		background: var(--bg-elevated);
		color: var(--text-secondary);
		border: 1px solid var(--border-subtle);
	}

	.role-badge.admin {
		background: color-mix(in srgb, #eab308 20%, transparent);
		color: #facc15;
		border-color: color-mix(in srgb, #eab308 40%, transparent);
	}

	.profile-details {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
		border-top: 1px solid var(--border-subtle);
		border-bottom: 1px solid var(--border-subtle);
		padding: 1.25rem 0;
	}

	.detail-item {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		align-items: center;
		gap: 1rem;
		font-size: 0.92rem;
	}

	.detail-label {
		color: var(--text-secondary);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.detail-value {
		font-weight: 600;
		color: var(--text-primary);
		min-width: 0;
		text-align: right;
	}

	.detail-value.active {
		color: var(--accent-green);
		display: flex;
		align-items: center;
		gap: 0.35rem;
	}

	.detail-value.mono {
		font-family: monospace;
		font-size: 0.8rem;
		color: var(--text-muted);
		overflow-wrap: anywhere;
	}

	.danger-card {
		grid-column: 1 / -1;
		border-color: color-mix(in srgb, var(--danger, #d75b5b) 40%, var(--border-ui));
	}

	.data-control-header {
		display: grid;
		gap: 0.45rem;
		max-width: 48rem;
	}

	.data-control-header h3 {
		font-size: 1.15rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin: 0;
	}

	.data-control-header .subtitle {
		margin: 0;
		line-height: 1.55;
	}

	.danger-card > .card-actions {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
	}
	.btn-danger-soft {
		border-color: color-mix(in srgb, var(--danger, #d75b5b) 55%, var(--border-ui));
		color: var(--danger, #d75b5b);
		background: color-mix(in srgb, var(--danger, #d75b5b) 8%, transparent);
	}
	.delete-form {
		display: grid;
		gap: 0.6rem;
		margin-top: 1rem;
		padding-top: 1rem;
		border-top: 1px solid var(--border-ui);
	}
	.delete-form label { color: var(--text-secondary); font-size: 0.85rem; }
	.delete-form input {
		padding: 0.65rem 0.8rem;
		border: 1px solid var(--border-ui);
		border-radius: var(--radius-control, 8px);
		background: var(--bg-panel);
		color: inherit;
	}
	.delete-warning { color: var(--danger, #d75b5b); font-size: 0.9rem; font-weight: 600; }
	.delete-error { color: var(--danger, #d75b5b); font-size: 0.85rem; }

	.card-actions {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.section-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
	}

	.section-header h3 {
		font-size: 1.15rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin: 0;
	}

	.session-count {
		font-size: 0.85rem;
		color: var(--text-muted);
		background: var(--bg-elevated);
		padding: 0.2rem 0.6rem;
		border-radius: 20px;
		flex: 0 0 auto;
	}

	.empty-text {
		color: var(--text-muted);
		font-size: 0.9rem;
	}

	.sessions-list {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
	}

	.session-row {
		display: flex;
		align-items: center;
		gap: 0.85rem;
		padding: 0.85rem;
		border-radius: 12px;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
	}

	.session-icon {
		font-size: 1.4rem;
		color: var(--accent-green);
		flex: 0 0 auto;
	}

	.session-info {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.session-title {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 0.92rem;
		color: var(--text-primary);
		flex-wrap: wrap;
	}

	.current-badge {
		font-size: 0.72rem;
		padding: 0.15rem 0.5rem;
		border-radius: 6px;
		background: color-mix(in srgb, var(--accent-green) 20%, transparent);
		color: var(--accent-green);
		font-weight: 700;
	}

	.session-meta {
		font-size: 0.8rem;
		color: var(--text-muted);
		display: flex;
		align-items: center;
		gap: 0.4rem;
		flex-wrap: wrap;
		line-height: 1.45;
	}

	.btn-revoke {
		background: transparent;
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.35rem 0.75rem;
		border-radius: 8px;
		font-size: 0.8rem;
		font-weight: 600;
		cursor: pointer;
		transition: all 0.2s ease;
		flex: 0 0 auto;
	}

	.btn-revoke:hover {
		background: #ef4444;
		color: #ffffff;
		border-color: #ef4444;
	}

	.spinner {
		animation: spin 1s linear infinite;
	}

	@keyframes spin {
		100% {
			transform: rotate(360deg);
		}
	}

	@media (max-width: 900px) {
		.account-grid {
			grid-template-columns: minmax(0, 1fr);
		}
		.deletion-options { grid-template-columns: 1fr; }

		.danger-card {
			grid-column: auto;
		}
	}

	@media (max-width: 540px) {
		.account-page {
			gap: 1.4rem;
		}

		.page-header h1 {
			font-size: clamp(1.55rem, 8vw, 2rem);
			align-items: flex-start;
		}

		.card {
			padding: 1.25rem;
			border-radius: 16px;
			gap: 1.2rem;
		}

		.section-header {
			align-items: flex-start;
			flex-wrap: wrap;
		}

		.session-row {
			display: grid;
			grid-template-columns: auto minmax(0, 1fr);
			align-items: start;
		}

		.btn-revoke {
			grid-column: 2;
			justify-self: start;
		}

		.danger-card > .card-actions {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
