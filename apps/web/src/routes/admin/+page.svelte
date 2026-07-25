<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from '$lib/stores/toast.svelte';

	let systemStatus = $state<any>(null);
	let users = $state<any[]>([]);
	let feedHealth = $state<any[]>([]);
	let isLoading = $state(true);
	let errorMsg = $state('');

	onMount(() => {
		loadAdminData();
	});

	async function loadAdminData() {
		isLoading = true;
		errorMsg = '';

		try {
			const [statusRes, usersRes, healthRes] = await Promise.all([
				fetch('/api/v1/admin/status'),
				fetch('/api/v1/admin/users'),
				fetch('/api/v1/admin/feed-health')
			]);

			if (!statusRes.ok || !usersRes.ok || !healthRes.ok) {
				errorMsg = 'Admin access denied or unauthorized.';
				return;
			}

			systemStatus = await statusRes.json();
			const userData = await usersRes.json();
			users = userData.users || [];
			const healthData = await healthRes.json();
			feedHealth = healthData.feeds || [];
		} catch (err: any) {
			errorMsg = 'Network error fetching admin metrics.';
		} finally {
			isLoading = false;
		}
	}

	async function handleToggleSuspend(userId: string, currentSuspended: boolean) {
		try {
			const res = await fetch(`/api/v1/admin/users/${userId}/suspend`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ suspend: !currentSuspended })
			});
			if (res.ok) loadAdminData();
		} catch (_) {}
	}

	async function handleRefreshFeed(podcastId: string) {
		try {
			const res = await fetch(`/api/v1/admin/podcasts/${podcastId}/refresh`, { method: 'POST' });
			if (res.ok) {
				toast.success('Feed refresh requested.');
				loadAdminData();
			}
		} catch (_) {}
	}
</script>

<div class="admin-page">
	<div class="admin-head">
		<h2><i class="ph-fill ph-shield-star" aria-hidden="true"></i> Admin</h2>
		<p class="admin-sub">System metrics, user moderation and feed health.</p>
	</div>

	{#if isLoading}
		<div class="loading">Loading admin dashboard...</div>
	{:else if errorMsg}
		<div class="error-banner">{errorMsg}</div>
	{:else}
		<!-- System Metrics -->
		<section class="card">
			<h3>System Operational Metrics</h3>
			{#if systemStatus}
				<div class="metrics-grid">
					<div class="metric-box">
						<span class="val">{systemStatus.user_count}</span>
						<span class="lbl">Total Users</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.podcast_count}</span>
						<span class="lbl">Podcasts</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.episode_count}</span>
						<span class="lbl">Episodes</span>
					</div>
					<div class="metric-box">
						<span class="val">{(systemStatus.database_size_bytes / (1024 * 1024)).toFixed(2)} MB</span>
						<span class="lbl">DB Size</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.worker_running ? 'Active' : 'Stopped'}</span>
						<span class="lbl">Worker Status</span>
					</div>
				</div>
			{/if}
		</section>

		<!-- Users Table -->
		<section class="card">
			<h3>Registered Users ({users.length})</h3>
			{#if users.length === 0}
				<p class="empty-note">No registered users yet.</p>
			{:else}
			<div class="table-scroll">
			<table class="admin-table">
				<thead>
					<tr>
						<th>Username</th>
						<th>Role</th>
						<th>Active Sessions</th>
						<th>Status</th>
						<th>Actions</th>
					</tr>
				</thead>
				<tbody>
					{#each users as user}
						<tr>
							<td><strong>{user.username}</strong></td>
							<td>{user.role}</td>
							<td>{user.active_sessions}</td>
							<td>
								<span class="badge" class:suspended={user.is_suspended}>
									{user.is_suspended ? 'Suspended' : 'Active'}
								</span>
							</td>
							<td>
								<button class="btn-sm" onclick={() => handleToggleSuspend(user.id, user.is_suspended)}>
									{user.is_suspended ? 'Restore' : 'Suspend'}
								</button>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
			</div>
			{/if}
		</section>

		<!-- Feed Health Table -->
		<section class="card">
			<h3>Feed Health & Error Status</h3>
			{#if feedHealth.length === 0}
				<p class="empty-note">No feeds are being tracked yet.</p>
			{:else}
			<div class="table-scroll">
			<table class="admin-table">
				<thead>
					<tr>
						<th>Podcast Title</th>
						<th>Errors</th>
						<th>Category</th>
						<th>HTTP Status</th>
						<th>Actions</th>
					</tr>
				</thead>
				<tbody>
					{#each feedHealth as feed}
						<tr>
							<td>{feed.title || feed.feed_url}</td>
							<td>{feed.consecutive_errors}</td>
							<td>{feed.last_error_category || 'OK'}</td>
							<td>{feed.last_http_status || '-'}</td>
							<td>
								<button class="btn-sm" onclick={() => handleRefreshFeed(feed.id)}>Refresh</button>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
			</div>
			{/if}
		</section>
	{/if}
</div>

<style>
	.admin-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.admin-head h2 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.admin-head h2 :global(.ph-fill) { color: var(--accent-green); }
	.admin-sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		padding: 1.5rem;
	}

	.metrics-grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
		gap: 1rem;
		margin-top: 1rem;
	}

	.metric-box {
		background: var(--bg-primary);
		padding: 1rem;
		border-radius: 6px;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.metric-box .val {
		font-size: 1.5rem;
		font-weight: 700;
		color: var(--accent-green);
	}

	.metric-box .lbl {
		font-size: 0.85rem;
		color: var(--text-secondary);
	}

	/* Let wide tables scroll on their own instead of blowing out the mobile layout. */
	.table-scroll {
		width: 100%;
		overflow-x: auto;
		-webkit-overflow-scrolling: touch;
	}

	.empty-note {
		color: var(--text-muted);
		font-size: 0.9rem;
		margin-top: 0.75rem;
	}

	.admin-table {
		width: 100%;
		border-collapse: collapse;
		margin-top: 1rem;
		min-width: 480px;
	}

	.admin-table th, .admin-table td {
		padding: 0.75rem;
		text-align: left;
		border-bottom: 1px solid var(--border-subtle);
	}

	.badge {
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		background: var(--accent-light);
		color: var(--accent-green-hover);
		font-size: 0.8rem;
		font-weight: 600;
	}

	.badge.suspended {
		background: #f8d7da;
		color: #721c24;
	}

	.btn-sm {
		font-size: 0.8rem;
		padding: 0.3rem 0.6rem;
		border-radius: 4px;
		border: 1px solid var(--border-subtle);
		background: var(--bg-primary);
	}

	.error-banner {
		padding: 1rem;
		background: #f8d7da;
		color: #721c24;
		border-radius: 6px;
	}
</style>
