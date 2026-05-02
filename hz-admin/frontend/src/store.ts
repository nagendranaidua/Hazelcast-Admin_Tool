import { configureStore, createSlice, PayloadAction } from '@reduxjs/toolkit';

interface Cluster {
  id: number;
  name: string;
  environment: string;
  majorVersion: string;
  enabled: boolean;
}

interface UIState {
  sidebarOpen: boolean;
  selectedClusterId: number | null;
}

interface ClustersState {
  items: Cluster[];
  loading: boolean;
  error: string | null;
}

// UI State Slice
const uiSlice = createSlice({
  name: 'ui',
  initialState: {
    sidebarOpen: true,
    selectedClusterId: null,
  } as UIState,
  reducers: {
    toggleSidebar: (state) => {
      state.sidebarOpen = !state.sidebarOpen;
    },
    setSidebarOpen: (state, action: PayloadAction<boolean>) => {
      state.sidebarOpen = action.payload;
    },
    setSelectedClusterId: (state, action: PayloadAction<number | null>) => {
      state.selectedClusterId = action.payload;
    },
  },
});

// Clusters Slice
const clustersSlice = createSlice({
  name: 'clusters',
  initialState: {
    items: [],
    loading: false,
    error: null,
  } as ClustersState,
  reducers: {
    setClusters: (state, action: PayloadAction<Cluster[]>) => {
      state.items = action.payload;
    },
    setLoading: (state, action: PayloadAction<boolean>) => {
      state.loading = action.payload;
    },
    setError: (state, action: PayloadAction<string | null>) => {
      state.error = action.payload;
    },
    addCluster: (state, action: PayloadAction<Cluster>) => {
      state.items.push(action.payload);
    },
    removeCluster: (state, action: PayloadAction<number>) => {
      state.items = state.items.filter(c => c.id !== action.payload);
    },
    updateCluster: (state, action: PayloadAction<Partial<Cluster> & { id: number }>) => {
      const idx = state.items.findIndex(c => c.id === action.payload.id);
      if (idx !== -1) {
        state.items[idx] = { ...state.items[idx], ...action.payload };
      }
    },
  },
});

export const store = configureStore({
  reducer: {
    ui: uiSlice.reducer,
    clusters: clustersSlice.reducer,
  },
});

export const uiActions = uiSlice.actions;
export const clustersActions = clustersSlice.actions;

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;

export default store;

