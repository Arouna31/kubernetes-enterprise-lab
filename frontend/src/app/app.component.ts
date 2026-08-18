import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

interface Hotel {
  id: number;
  name: string;
  city: string;
  rooms: number;
}

interface AppInfo {
  application: string;
  environment: string;
  instance: string;
  version: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  private readonly http = inject(HttpClient);

  readonly hotels = signal<Hotel[]>([]);
  readonly info = signal<AppInfo | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly totalRooms = computed(() =>
    this.hotels().reduce((total, hotel) => total + hotel.rooms, 0)
  );

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);

    this.http.get<Hotel[]>('/api/hotels').subscribe({
      next: hotels => {
        this.hotels.set(hotels);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('API indisponible — parfait pour un futur exercice Kubernetes 😈');
        this.loading.set(false);
      }
    });

    this.http.get<AppInfo>('/api/info').subscribe({
      next: info => this.info.set(info),
      error: () => this.info.set(null)
    });
  }
}
