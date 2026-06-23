import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-pagination',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pagination.component.html',
  styleUrls: ['./pagination.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaginationComponent {
  @Input() totalPages: number = 0;
  @Input() currentPage: number = 1;
  @Output() pageChange = new EventEmitter<number>();

  /**
   * Logic to get the 3 visible pages:
   * - If page 1: [1, 2, 3]
   * - If last page: [n-2, n-1, n]
   * - Otherwise: [current-1, current, current+1]
   */
  getVisiblePages(): number[] {
    if (this.totalPages <= 3) {
      return Array.from({ length: this.totalPages }, (_, i) => i + 1);
    }

    if (this.currentPage <= 1) {
      return [1, 2, 3];
    }

    if (this.currentPage >= this.totalPages) {
      return [this.totalPages - 2, this.totalPages - 1, this.totalPages];
    }

    return [this.currentPage - 1, this.currentPage, this.currentPage + 1];
  }

  onPageClick(page: number): void {
    if (page >= 1 && page <= this.totalPages && page !== this.currentPage) {
      this.pageChange.emit(page);
    }
  }

  previousPage(): void {
    if (this.currentPage > 1) {
      this.pageChange.emit(this.currentPage - 1);
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages) {
      this.pageChange.emit(this.currentPage + 1);
    }
  }
}
